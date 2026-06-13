package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Query;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.transaction.UnitOfWork;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.sonatype.nexus.security.SecurityHelper;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;

/**
 * Service for remote repository sync.
 * Uses Nexus official ProxyFacet to re-fetch assets from remote storage.
 * Features:
 * - Uses ProxyFacet.get() to trigger re-download from remote
 * - Clears local cache (blob reference) before re-fetch to force fresh download
 * - Clears negative cache entries so previously 404 assets can be retried
 * - Dedup: same source directory re-submitted keeps only latest task
 * - Automatic cleanup of completed task info to prevent memory leaks
 */
@Named
@Singleton
public class SyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncService.class);

  /** Maximum time (ms) to keep completed task info before cleanup (30 minutes) */
  private static final long TASK_INFO_TTL_MS = 30 * 60 * 1000L;

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;

  private final Map<String, SyncTaskInfo> taskInfos = new ConcurrentHashMap<>();

  @Inject
  public SyncService(final RepositoryManager repositoryManager,
                      final TaskExecutorService taskExecutor,
                      final TaskCacheManager cacheManager,
                      final PermissionChecker permissionChecker,
                      final SecurityHelper securityHelper)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
    this.securityHelper = securityHelper;
  }

  /**
   * Execute sync of a remote repository path.
   * If the same source directory already has a pending task, the old task is cancelled
   * and marked as migrated to the new task.
   *
   * @return the new sync task ID
   */
  public String sync(final SyncRequest request) {
    request.validate();

    // Check sync permission (also verifies it's a proxy repo)
    permissionChecker.checkSyncPermission(request.getRepositoryName(), request.getFormat());

    // Check queue capacity
    if (!taskExecutor.hasSyncQueueCapacity()) {
      throw new IllegalStateException("Sync queue is full. Please wait for existing tasks to complete.");
    }

    String username = permissionChecker.getCurrentUsername();

    // Capture current Shiro subject before submitting to thread pool
    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;

    // Pre-generate taskId so it's available inside the lambda
    final String preTaskId = "sync-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    // Submit to thread pool with dedup handling
    String taskId = taskExecutor.submitSyncTask(() -> {
      // Bind Shiro subject to this thread to preserve security context
      if (threadState != null) {
        threadState.bind();
      }
      try {
        SyncTaskInfo taskInfo = new SyncTaskInfo();
        taskInfo.setTaskId(preTaskId);
        taskInfo.setSourceRepository(request.getRepositoryName());
        taskInfo.setPath(request.getPath());
        taskInfo.setDirectory(request.isDirectory());
        taskInfo.setFormat(request.getFormat());
        taskInfo.setUsername(username);
        taskInfo.setStartTime(System.currentTimeMillis());
        taskInfo.setStatus(TaskStatus.RUNNING);

        try {
          Repository repo = repositoryManager.get(request.getRepositoryName());
          if (repo == null) {
            throw new IllegalArgumentException("Repository not found: " + request.getRepositoryName());
          }

          // Verify it's a proxy repository
          if (!"proxy".equals(repo.getType().getValue())) {
            throw new IllegalArgumentException("Repository is not a proxy type: " + request.getRepositoryName());
          }

          // Determine target repository (local cache of the proxy repo)
          String targetRepo = repo.getName();
          taskInfo.setTargetRepository(targetRepo);

          // Create task cache
          cacheManager.createTaskCache(preTaskId);

          // Execute sync using official ProxyFacet
          List<SyncTaskInfo.SyncFileDetail> syncedFiles;
          if (request.isDirectory()) {
            syncedFiles = syncDirectory(repo, request.getPath());
          }
          else {
            syncedFiles = syncFile(repo, request.getPath());
          }

          taskInfo.setFileDetails(syncedFiles);
          taskInfo.setStatus(TaskStatus.COMPLETED);
          taskInfo.setEndTime(System.currentTimeMillis());
          taskInfo.setResult("Synced " + syncedFiles.size() + " items");

          log.info("Sync task completed: {} items synced from {}:{}",
              syncedFiles.size(), request.getRepositoryName(), request.getPath());

        }
        catch (Exception e) {
          log.error("Sync task failed: {}", e.getMessage(), e);
          taskInfo.setStatus(TaskStatus.FAILED);
          taskInfo.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          taskInfo.setEndTime(System.currentTimeMillis());
          taskInfo.setResult("Failed: " + sanitizeErrorMessage(e.getMessage()));
        }

        taskInfos.put(taskInfo.getTaskId(), taskInfo);

        return new TaskExecutorService.SyncTaskCallback() {
          @Override
          public String getTaskId() { return taskInfo.getTaskId(); }
          @Override
          public TaskStatus getStatus() { return taskInfo.getStatus(); }
          @Override
          public String getErrorMessage() { return taskInfo.getErrorMessage(); }
        };
      }
      finally {
        // Clean up thread-local Shiro state
        if (threadState != null) {
          threadState.clear();
        }
      }
    }, String.format("Sync %s from %s", request.getPath(), request.getRepositoryName()),
        request.getRepositoryName(), request.getPath(), preTaskId);

    // Create initial task info record
    SyncTaskInfo initialInfo = new SyncTaskInfo();
    initialInfo.setTaskId(taskId);
    initialInfo.setSourceRepository(request.getRepositoryName());
    initialInfo.setPath(request.getPath());
    initialInfo.setDirectory(request.isDirectory());
    initialInfo.setFormat(request.getFormat());
    initialInfo.setUsername(username);
    initialInfo.setStatus(TaskStatus.PENDING);
    initialInfo.setStartTime(System.currentTimeMillis());
    taskInfos.put(taskId, initialInfo);

    log.info("Sync task {} created for {}:{} by {}", taskId, request.getRepositoryName(), request.getPath(), username);

    return taskId;
  }

  /**
   * Get sync task info by ID.
   * Also triggers cleanup of expired task info entries.
   */
  public SyncTaskInfo getTaskInfo(final String taskId) {
    cleanupExpiredTaskInfos();
    SyncTaskInfo info = taskInfos.get(taskId);
    if (info != null) {
      // Update status from executor
      TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
      if (status != null) {
        info.setStatus(status);
      }
      // If task is in terminal state, clean up the executor handle
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(taskId);
      }
    }
    return info;
  }

  /**
   * Get all sync tasks (for queue display).
   * Also triggers cleanup of expired task info entries.
   */
  public List<SyncTaskInfo> getAllSyncTasks() {
    cleanupExpiredTaskInfos();
    List<SyncTaskInfo> tasks = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      // Update status from executor
      TaskStatus status = taskExecutor.getSyncTaskStatus(entry.getKey());
      if (status != null) {
        info.setStatus(status);
      }
      // Clean up executor handles for terminal tasks
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(entry.getKey());
      }
      tasks.add(info);
    }
    // Sort by start time descending
    tasks.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));
    return tasks;
  }

  /**
   * Get active (pending/running) sync tasks.
   */
  public List<SyncTaskInfo> getActiveSyncTasks() {
    List<SyncTaskInfo> active = new ArrayList<>();
    for (SyncTaskInfo info : getAllSyncTasks()) {
      if (info.getStatus() == TaskStatus.PENDING || info.getStatus() == TaskStatus.RUNNING) {
        active.add(info);
      }
    }
    return active;
  }

  /**
   * Clean up task info entries that have been completed for longer than TASK_INFO_TTL_MS.
   * This prevents memory leaks from accumulated completed task data.
   */
  private void cleanupExpiredTaskInfos() {
    long now = System.currentTimeMillis();
    List<String> expiredTaskIds = new ArrayList<>();

    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = info.getStatus();
      // Only clean up terminal state tasks that have expired
      if ((status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.MIGRATED)
          && info.getEndTime() > 0
          && (now - info.getEndTime()) > TASK_INFO_TTL_MS) {
        expiredTaskIds.add(entry.getKey());
      }
    }

    for (String taskId : expiredTaskIds) {
      taskInfos.remove(taskId);
      taskExecutor.cleanupSyncTaskHandle(taskId);
      log.debug("Expired task info cleaned up: {}", taskId);
    }

    if (!expiredTaskIds.isEmpty()) {
      log.info("Cleaned up {} expired sync task info entries", expiredTaskIds.size());
    }
  }

  /**
   * Sync all files in a directory from remote using ProxyFacet.
   * Steps:
   * 1. Get the remote URL from repository configuration
   * 2. Fetch remote directory listing to discover ALL files (including uncached)
   * 3. Also list locally cached assets
   * 4. Merge both lists (dedup)
   * 5. For each file, delete local cache, invalidate negative cache, and re-fetch via ProxyFacet
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDirectory(final Repository repo, final String directoryPath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      Set<String> allAssetNames = new LinkedHashSet<>();

      log.info("Starting directory sync for {}:{}", repo.getName(), directoryPath);

      // Step 1: List locally cached assets
      List<String> localAssets = listLocalAssets(repo, directoryPath);
      allAssetNames.addAll(localAssets);
      log.info("Found {} local assets in {}:{}", localAssets.size(), repo.getName(), directoryPath);

      // Step 2: Fetch remote directory listing to discover uncached files
      List<String> remoteAssets = listRemoteAssets(repo, directoryPath);
      allAssetNames.addAll(remoteAssets);
      log.info("Found {} remote assets in {}:{}", remoteAssets.size(), repo.getName(), directoryPath);

      log.info("Sync directory {}: {} local assets, {} remote assets, {} total unique",
          directoryPath, localAssets.size(), remoteAssets.size(), allAssetNames.size());

      // Step 3: Sync each asset using ProxyFacet
      for (String assetName : allAssetNames) {
        // Skip directory entries (ending with /)
        if (assetName.endsWith("/")) {
          continue;
        }

        SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
            assetName, determineType(assetName));

        try {
          syncAssetViaProxy(repo, assetName);
          detail.setStatus("success");
        }
        catch (Exception e) {
          log.error("Failed to sync asset {}: {}", assetName, e.getMessage());
          detail.setStatus("failed");
          detail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        }

        details.add(detail);
      }
    }
    catch (Exception e) {
      log.error("Failed to sync directory {}: {}", directoryPath, e.getMessage(), e);
      throw new RuntimeException("Directory sync failed", e);
    }

    return details;
  }

  /**
   * List locally cached assets in a directory.
   */
  private List<String> listLocalAssets(final Repository repo, final String directoryPath) {
    List<String> assetNames = new ArrayList<>();
    try {
      StorageFacet storageFacet = repo.facet(StorageFacet.class);
      UnitOfWork.begin(storageFacet.txSupplier());
      try {
        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(repo);

        Query query = Query.builder()
            .where("name").like(escapeLike(directoryPath) + "%")
            .build();

        Iterable<Asset> assets = tx.findAssets(query, Collections.singletonList(repo));
        for (Asset asset : assets) {
          assetNames.add(asset.name());
        }
      }
      finally {
        UnitOfWork.end();
      }
    }
    catch (Exception e) {
      log.warn("Failed to list local assets for {}: {}", directoryPath, e.getMessage());
    }
    return assetNames;
  }

  /**
   * List remote assets by fetching the remote directory listing.
   * Parses HTML directory index pages to extract file links.
   * Also tries to use proxy repo's configured HTTP client for authentication.
   */
  private List<String> listRemoteAssets(final Repository repo, final String directoryPath) {
    List<String> remoteAssets = new ArrayList<>();

    try {
      // Get remote URL from repository configuration
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        log.warn("No configuration found for repository {}", repo.getName());
        return remoteAssets;
      }

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) {
        log.warn("No proxy configuration found for repository {}", repo.getName());
        return remoteAssets;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        log.warn("No remote URL configured for proxy repository {}", repo.getName());
        return remoteAssets;
      }

      // Get authentication config if available
      String authUsername = null;
      String authPassword = null;
      if (attributes.containsKey("httpclient")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> httpClientAttrs = attributes.get("httpclient");
        @SuppressWarnings("unchecked")
        Map<String, Object> authAttrs = (Map<String, Object>) httpClientAttrs.get("authentication");
        if (authAttrs != null) {
          authUsername = (String) authAttrs.get("username");
          authPassword = (String) authAttrs.get("password");
        }
      }

      // Normalize URLs
      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }
      String dirUrl = remoteUrl + directoryPath;
      if (!dirUrl.endsWith("/")) {
        dirUrl += "/";
      }

      log.info("Fetching remote directory listing from: {} (auth: {})", dirUrl, authUsername != null ? "yes" : "no");

      // Fetch remote directory listing
      HttpURLConnection conn = (HttpURLConnection) new URL(dirUrl).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");

      // Add basic auth if configured
      if (authUsername != null && authPassword != null) {
        String auth = authUsername + ":" + authPassword;
        String encoded = java.util.Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
      }

      int responseCode = conn.getResponseCode();
      log.info("Remote directory listing response: HTTP {} for {}", responseCode, dirUrl);

      if (responseCode != 200) {
        log.warn("Failed to fetch remote directory listing: HTTP {} for {}", responseCode, dirUrl);
        // Try reading error stream for more info
        try {
          if (conn.getErrorStream() != null) {
            byte[] errorBytes = new byte[512];
            int read = conn.getErrorStream().read(errorBytes);
            if (read > 0) {
              log.warn("Error response: {}", new String(errorBytes, 0, read, "UTF-8"));
            }
          }
        }
        catch (Exception ignored) { }
        conn.disconnect();
        return remoteAssets;
      }

      // Parse HTML to extract links
      StringBuilder html = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = reader.readLine()) != null) {
          html.append(line).append("\n");
        }
      }
      conn.disconnect();

      String htmlStr = html.toString();
      log.debug("Remote directory HTML (first 500 chars): {}", htmlStr.substring(0, Math.min(500, htmlStr.length())));

      // Extract href links from HTML - common patterns:
      // <a href="filename">, <a href='./filename'>, <a href="filename">
      Pattern linkPattern = Pattern.compile(
          "<a[^>]+href\\s*=\\s*[\"'](?!(?:mailto:|javascript:|\\?|/|#))([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);
      Matcher matcher = linkPattern.matcher(htmlStr);

      while (matcher.find()) {
        String href = matcher.group(1);
        // Remove leading ./ if present
        if (href.startsWith("./")) {
          href = href.substring(2);
        }
        // Skip parent directory link and root
        if (href.equals("../") || href.equals("/") || href.equals(".")) {
          continue;
        }
        // Skip query strings and anchors
        if (href.contains("?") || href.contains("#")) {
          continue;
        }

        // Build full asset path
        String assetPath;
        if (href.endsWith("/")) {
          // Subdirectory - include it for recursive sync
          assetPath = directoryPath + (directoryPath.endsWith("/") ? "" : "/") + href;
        }
        else {
          // File
          assetPath = directoryPath + (directoryPath.endsWith("/") ? "" : "/") + href;
        }

        remoteAssets.add(assetPath);
      }

      log.info("Found {} remote assets in directory {}", remoteAssets.size(), directoryPath);

    }
    catch (Exception e) {
      log.error("Failed to list remote assets for {}: {}", directoryPath, e.getMessage(), e);
    }

    return remoteAssets;
  }

  /**
   * Sync a single file from remote using ProxyFacet.
   */
  private List<SyncTaskInfo.SyncFileDetail> syncFile(final Repository repo, final String filePath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
          filePath, determineType(filePath));

      try {
        syncAssetViaProxy(repo, filePath);
        detail.setStatus("success");
      }
      catch (Exception e) {
        detail.setStatus("failed");
        detail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
      }

      details.add(detail);
    }
    catch (Exception e) {
      log.error("Failed to sync file {}: {}", filePath, e.getMessage(), e);
      throw new RuntimeException("File sync failed", e);
    }

    return details;
  }

  /**
   * Sync a single asset using Nexus official ProxyFacet.
   * This method:
   * 1. Deletes the local cached asset (clears blob reference) to force re-download
   * 2. Invalidates negative cache for the path
   * 3. Uses ProxyFacet to re-fetch the asset from the remote storage
   */
  private void syncAssetViaProxy(final Repository repo, final String assetName) throws Exception {
    log.debug("Syncing asset {} from remote via ProxyFacet", assetName);

    // Step 1: Delete the local cached asset to force re-download
    deleteAssetCache(repo, assetName);

    // Step 2: Invalidate negative cache for this path
    invalidateNegativeCache(repo, assetName);

    // Step 3: Use ProxyFacet to re-fetch from remote
    try {
      ProxyFacet proxyFacet = repo.facet(ProxyFacet.class);
      if (proxyFacet != null) {
        // Build a GET request to trigger proxy fetch
        Request request = new Request.Builder()
            .action("GET")
            .path("/" + assetName)
            .build();

        // Create context and invoke proxy get
        Context context = new Context(repo, request);
        Content content = proxyFacet.get(context);

        if (content != null) {
          log.info("Successfully re-fetched asset {} from remote", assetName);
        }
        else {
          log.warn("ProxyFacet.get() returned null for asset {}, asset may not exist on remote", assetName);
        }
      }
      else {
        log.warn("Repository {} does not have ProxyFacet, skipping proxy fetch", repo.getName());
      }
    }
    catch (IOException e) {
      log.error("Failed to re-fetch asset {} from remote: {}", assetName, e.getMessage());
      throw new RuntimeException("Failed to re-fetch from remote: " + e.getMessage(), e);
    }
  }

  /**
   * Delete the local cached asset (clear blob reference) to force re-download.
   * This removes the asset's blob reference so that ProxyFacet will re-download it.
   */
  private void deleteAssetCache(final Repository repo, final String assetName) throws Exception {
    StorageFacet storageFacet = repo.facet(StorageFacet.class);
    UnitOfWork.begin(storageFacet.txSupplier());
    try {
      StorageTx tx = UnitOfWork.currentTx();
      Bucket bucket = tx.findBucket(repo);

      Asset asset = tx.findAssetWithProperty("name", assetName, bucket);
      if (asset != null) {
        // Delete the asset completely so it will be re-created from remote
        tx.deleteAsset(asset);
        tx.commit();
        log.debug("Deleted cached asset: {}", assetName);
      }
      else {
        log.debug("Asset {} not found in local cache, will be fetched fresh from remote", assetName);
      }
    }
    finally {
      UnitOfWork.end();
    }
  }

  /**
   * Invalidate negative cache entries for a given path.
   * This allows previously 404'd assets to be retried from remote.
   */
  @SuppressWarnings("unchecked")
  private void invalidateNegativeCache(final Repository repo, final String assetName) {
    try {
      // Try to get NegativeCacheFacet if available
      Class<?> negCacheFacetClass = null;
      try {
        negCacheFacetClass = Class.forName("org.sonatype.nexus.repository.cache.NegativeCacheFacet");
      }
      catch (ClassNotFoundException e) {
        // NegativeCacheFacet not available in this Nexus version, skip
        log.debug("NegativeCacheFacet not available, skipping negative cache invalidation");
        return;
      }

      Object negCacheFacet = repo.facet((Class) negCacheFacetClass);
      if (negCacheFacet != null) {
        // Use reflection to call invalidate() method
        try {
          java.lang.reflect.Method invalidateMethod = negCacheFacet.getClass().getMethod("invalidate", String.class);
          invalidateMethod.invoke(negCacheFacet, assetName);
          log.debug("Invalidated negative cache for: {}", assetName);
        }
        catch (NoSuchMethodException e) {
          // Try no-arg invalidate for entire repository
          try {
            java.lang.reflect.Method invalidateAllMethod = negCacheFacet.getClass().getMethod("invalidate");
            invalidateAllMethod.invoke(negCacheFacet);
            log.debug("Invalidated all negative cache entries for repo {}", repo.getName());
          }
          catch (Exception e2) {
            log.debug("Could not invalidate negative cache: {}", e2.getMessage());
          }
        }
      }
    }
    catch (Exception e) {
      // Negative cache invalidation is best-effort, don't fail the sync
      log.debug("Negative cache invalidation skipped: {}", e.getMessage());
    }
  }

  /**
   * Determine the type of an asset based on its path.
   */
  private String determineType(final String path) {
    if (path == null) return "file";
    if (path.contains("/blobs/") || path.contains("/manifests/") || path.endsWith(".manifest")) {
      return "image";
    }
    if (path.endsWith("/")) {
      return "directory";
    }
    return "file";
  }

  private String escapeLike(final String input) {
    if (input == null) return "";
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }
}
