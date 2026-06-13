package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
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
 * Uses Nexus official ContentFacet.get() to fetch assets from remote storage.
 *
 * Flow:
 * 1. Get remote file list (from same-instance REST API or HTTP directory listing)
 * 2. For each file, call ContentFacet.get(path)
 * 3. Nexus automatically: checks local cache -> if missing -> remote fetch -> save to BlobStore -> return Content
 *
 * This is the official Nexus-supported way to trigger proxy repository sync.
 */
@Named
@Singleton
public class SyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncService.class);

  /** Maximum time (ms) to keep completed task info before cleanup (30 minutes) */
  private static final long TASK_INFO_TTL_MS = 30 * 60 * 1000L;

  /** Nexus local base URL for internal API calls */
  private static final String LOCAL_NEXUS_BASE = "http://localhost:8081";

  /** Admin credentials for internal API calls */
  private static final String ADMIN_AUTH = "admin:admin123";

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

          // Execute sync using ContentFacet
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
   */
  public SyncTaskInfo getTaskInfo(final String taskId) {
    cleanupExpiredTaskInfos();
    SyncTaskInfo info = taskInfos.get(taskId);
    if (info != null) {
      TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
      if (status != null) {
        info.setStatus(status);
      }
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(taskId);
      }
    }
    return info;
  }

  /**
   * Get all sync tasks (for queue display).
   */
  public List<SyncTaskInfo> getAllSyncTasks() {
    cleanupExpiredTaskInfos();
    List<SyncTaskInfo> tasks = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = taskExecutor.getSyncTaskStatus(entry.getKey());
      if (status != null) {
        info.setStatus(status);
      }
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(entry.getKey());
      }
      tasks.add(info);
    }
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
   */
  private void cleanupExpiredTaskInfos() {
    long now = System.currentTimeMillis();
    List<String> expiredTaskIds = new ArrayList<>();

    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = info.getStatus();
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

  // ========================================================================
  // Directory sync: get remote file list, then ContentFacet.get() each file
  // ========================================================================

  /**
   * Sync all files in a directory from remote using ContentFacet.
   * Flow:
   * 1. Get remote file list (from remote repo REST API or HTTP directory listing)
   * 2. For each file, call ContentFacet.get(path) -> Nexus auto-fetches from remote if not cached
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDirectory(final Repository repo, final String directoryPath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      log.info("Starting directory sync for {}:{}", repo.getName(), directoryPath);

      // Step 1: Get remote file list
      List<String> remoteAssets = listRemoteAssets(repo, directoryPath);
      log.info("Found {} remote assets to sync in {}:{}", remoteAssets.size(), repo.getName(), directoryPath);

      // Step 2: Sync each asset using ContentFacet.get()
      for (String assetPath : remoteAssets) {
        // Skip directory entries
        if (assetPath.endsWith("/")) {
          continue;
        }

        SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
            assetPath, determineType(assetPath));

        try {
          syncAssetViaContentFacet(repo, assetPath);
          detail.setStatus("success");
        }
        catch (Exception e) {
          log.error("Failed to sync asset {}: {}", assetPath, e.getMessage());
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
   * Sync a single file from remote using ContentFacet.
   */
  private List<SyncTaskInfo.SyncFileDetail> syncFile(final Repository repo, final String filePath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
          filePath, determineType(filePath));

      try {
        syncAssetViaContentFacet(repo, filePath);
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
   * Sync a single asset using Nexus official ProxyFacet.get().
   *
   * This is the official Nexus way to trigger proxy repository sync:
   * ProxyFacet.get(context) -> checks local cache -> if missing/stale -> fetches from RemoteStorage
   * -> saves to BlobStore -> creates Asset -> returns Content
   *
   * Flow:
   * 1. Delete existing local cache (via REST API) to force fresh download
   * 2. Invalidate negative cache so previously 404'd assets can be retried
   * 3. Call ProxyFacet.get(context) which auto-fetches from remote
   */
  private void syncAssetViaContentFacet(final Repository repo, final String assetPath) throws Exception {
    log.info("Syncing asset {} via ProxyFacet.get()", assetPath);

    try {
      // Step 1: Delete existing local cache to force fresh download
      deleteAssetViaApi(repo.getName(), assetPath);

      // Step 2: Invalidate negative cache so previously 404'd assets can be retried
      invalidateNegativeCache(repo, assetPath);

      // Step 3: Use ProxyFacet.get() - Nexus official way to trigger proxy fetch
      // This triggers: check local -> if missing -> remote fetch -> save to BlobStore
      ProxyFacet proxyFacet = repo.facet(ProxyFacet.class);
      if (proxyFacet != null) {
        Request request = new Request.Builder()
            .action("GET")
            .path("/" + assetPath)
            .build();

        Context context = new Context(repo, request);
        Content content = proxyFacet.get(context);

        if (content != null) {
          log.info("Successfully synced asset {} from remote", assetPath);
        }
        else {
          log.warn("ProxyFacet.get() returned null for asset {}, asset may not exist on remote", assetPath);
        }
      }
      else {
        log.warn("Repository {} does not have ProxyFacet, skipping proxy fetch", repo.getName());
      }
    }
    catch (Exception e) {
      log.error("Failed to sync asset {} via ProxyFacet: {}", assetPath, e.getMessage());
      throw new RuntimeException("Failed to sync from remote: " + e.getMessage(), e);
    }
  }

  // ========================================================================
  // Remote file list: get file list from remote repository
  // ========================================================================

  /**
   * List remote assets by querying the remote repository.
   * Strategy:
   * 1. If remote URL points to a local Nexus repo, use REST API to list assets
   * 2. Otherwise, fall back to HTTP directory listing
   */
  private List<String> listRemoteAssets(final Repository repo, final String directoryPath) {
    try {
      // Get remote URL from repository configuration
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        log.warn("No configuration found for repository {}", repo.getName());
        return new ArrayList<>();
      }

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) {
        log.warn("No proxy configuration found for repository {}", repo.getName());
        return new ArrayList<>();
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        log.warn("No remote URL configured for proxy repository {}", repo.getName());
        return new ArrayList<>();
      }

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      // Strategy 1: Check if remote URL points to a local Nexus repo
      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        try {
          Repository remoteRepo = repositoryManager.get(remoteRepoName);
          if (remoteRepo != null) {
            log.info("Remote URL points to local Nexus repo: {}, listing via REST API", remoteRepoName);
            List<String> assets = listAssetsViaApi(remoteRepoName, directoryPath);
            log.info("Found {} assets from local repo {} in path {}", assets.size(), remoteRepoName, directoryPath);
            return assets;
          }
        }
        catch (Exception ignored) {
          // Repo not found locally, fall through to HTTP
        }
      }

      // Strategy 2: Fall back to HTTP directory listing
      log.info("Falling back to HTTP directory listing for remote: {}", remoteUrl);
      return listRemoteAssetsViaHttp(directoryPath, remoteUrl, attributes);

    }
    catch (Exception e) {
      log.error("Failed to list remote assets for {}: {}", directoryPath, e.getMessage(), e);
      return new ArrayList<>();
    }
  }

  /**
   * Extract repository name from a Nexus repository URL.
   * URL pattern: http://host:port/repository/<repo-name>/
   */
  private String extractRepoNameFromUrl(final String remoteUrl) {
    try {
      java.net.URL url = new java.net.URL(remoteUrl);
      String path = url.getPath();
      if (path.startsWith("/repository/")) {
        String repoPart = path.substring("/repository/".length());
        if (repoPart.endsWith("/")) {
          repoPart = repoPart.substring(0, repoPart.length() - 1);
        }
        int slashIdx = repoPart.indexOf('/');
        String repoName = (slashIdx > 0) ? repoPart.substring(0, slashIdx) : repoPart;
        if (!repoName.isEmpty()) {
          return repoName;
        }
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract repo name from URL {}: {}", remoteUrl, e.getMessage());
    }
    return null;
  }

  /**
   * List assets from a local Nexus repository via REST API.
   * Handles pagination via continuationToken.
   */
  private List<String> listAssetsViaApi(final String repoName, final String directoryPath) throws Exception {
    List<String> assetNames = new ArrayList<>();
    String normalizedDir = directoryPath;
    if (!normalizedDir.endsWith("/")) {
      normalizedDir = normalizedDir + "/";
    }

    String apiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/assets?repository=" + repoName;
    String continuationToken = null;

    do {
      String url = apiUrl;
      if (continuationToken != null) {
        url += "&continuationToken=" + continuationToken;
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Authorization", "Basic " + encodeAuth(ADMIN_AUTH));

      if (conn.getResponseCode() != 200) {
        log.warn("Assets API returned HTTP {} for {}", conn.getResponseCode(), url);
        conn.disconnect();
        return assetNames;
      }

      String json = readResponse(conn);

      // Parse items array
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection != null) {
        int pos = 0;
        while (pos < itemsSection.length()) {
          int objStart = itemsSection.indexOf("{", pos);
          if (objStart < 0) break;
          int objEnd = findMatchingBrace(itemsSection, objStart);
          if (objEnd < 0) break;

          String item = itemsSection.substring(objStart, objEnd + 1);
          String path = extractJsonValue(item, "path");

          if (path != null && path.startsWith(normalizedDir) && path.length() > normalizedDir.length()) {
            assetNames.add(path);
          }

          pos = objEnd + 1;
        }
      }

      continuationToken = extractJsonValue(json, "continuationToken");

    } while (continuationToken != null && !continuationToken.isEmpty());

    return assetNames;
  }

  /**
   * List remote assets via HTTP directory listing (for external remotes).
   * Parses HTML directory index pages to extract file links.
   */
  private List<String> listRemoteAssetsViaHttp(final String directoryPath,
      final String remoteUrl, final Map<String, Map<String, Object>> attributes) {
    List<String> remoteAssets = new ArrayList<>();

    try {
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

      String dirUrl = remoteUrl + directoryPath;
      if (!dirUrl.endsWith("/")) {
        dirUrl += "/";
      }

      log.info("Fetching remote directory listing from: {} (auth: {})", dirUrl, authUsername != null ? "yes" : "no");

      HttpURLConnection conn = (HttpURLConnection) new URL(dirUrl).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");

      if (authUsername != null && authPassword != null) {
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(authUsername + ":" + authPassword));
      }

      int responseCode = conn.getResponseCode();
      log.info("Remote directory listing response: HTTP {} for {}", responseCode, dirUrl);

      if (responseCode != 200) {
        log.warn("Failed to fetch remote directory listing: HTTP {} for {}", responseCode, dirUrl);
        conn.disconnect();
        return remoteAssets;
      }

      String html = readResponse(conn);

      // Parse HTML to extract links
      Pattern linkPattern = Pattern.compile(
          "<a[^>]+href\\s*=\\s*[\"'](?!(?:mailto:|javascript:|\\?|/|#))([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);
      Matcher matcher = linkPattern.matcher(html);

      while (matcher.find()) {
        String href = matcher.group(1);
        if (href.startsWith("./")) {
          href = href.substring(2);
        }
        if (href.equals("../") || href.equals("/") || href.equals(".")) {
          continue;
        }
        if (href.contains("?") || href.contains("#")) {
          continue;
        }

        String assetPath = directoryPath + (directoryPath.endsWith("/") ? "" : "/") + href;
        remoteAssets.add(assetPath);
      }

      log.info("Found {} remote assets via HTTP directory listing", remoteAssets.size());

    }
    catch (Exception e) {
      log.warn("Failed to list remote assets via HTTP for {}: {}", directoryPath, e.getMessage());
    }

    return remoteAssets;
  }

  // ========================================================================
  // Cache management: delete local cache + invalidate negative cache
  // ========================================================================

  /**
   * Delete a local cached asset via REST API.
   * This forces ContentFacet.get() to re-download from remote.
   */
  private void deleteAssetViaApi(final String repoName, final String assetPath) {
    try {
      // Find the asset ID via search API
      String assetId = findAssetIdViaApi(repoName, assetPath);
      if (assetId != null) {
        String deleteUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/assets/" + assetId;
        HttpURLConnection conn = (HttpURLConnection) new URL(deleteUrl).openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(ADMIN_AUTH));

        int responseCode = conn.getResponseCode();
        conn.disconnect();

        if (responseCode == 204 || responseCode == 200) {
          log.debug("Deleted cached asset via API: {}", assetPath);
        }
        else {
          log.warn("Failed to delete asset via API: HTTP {} for {}", responseCode, assetPath);
        }
      }
      else {
        log.debug("Asset {} not found in local cache, will be fetched fresh from remote", assetPath);
      }
    }
    catch (Exception e) {
      log.warn("Failed to delete cached asset {} via API: {}", assetPath, e.getMessage());
    }
  }

  /**
   * Find an asset ID by path using the Nexus REST API.
   */
  private String findAssetIdViaApi(final String repoName, final String assetPath) throws Exception {
    String apiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/assets?repository=" + repoName;
    String continuationToken = null;

    do {
      String url = apiUrl;
      if (continuationToken != null) {
        url += "&continuationToken=" + continuationToken;
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Authorization", "Basic " + encodeAuth(ADMIN_AUTH));

      if (conn.getResponseCode() != 200) {
        conn.disconnect();
        return null;
      }

      String json = readResponse(conn);

      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection != null) {
        int pos = 0;
        while (pos < itemsSection.length()) {
          int objStart = itemsSection.indexOf("{", pos);
          if (objStart < 0) break;
          int objEnd = findMatchingBrace(itemsSection, objStart);
          if (objEnd < 0) break;

          String item = itemsSection.substring(objStart, objEnd + 1);
          String path = extractJsonValue(item, "path");
          String id = extractJsonValue(item, "id");

          if (path != null && path.equals(assetPath)) {
            return id;
          }

          pos = objEnd + 1;
        }
      }

      continuationToken = extractJsonValue(json, "continuationToken");

    } while (continuationToken != null && !continuationToken.isEmpty());

    return null;
  }

  /**
   * Invalidate negative cache entries for a given path.
   * This allows previously 404'd assets to be retried from remote.
   */
  @SuppressWarnings("unchecked")
  private void invalidateNegativeCache(final Repository repo, final String assetPath) {
    try {
      Class<?> negCacheFacetClass = null;
      try {
        negCacheFacetClass = Class.forName("org.sonatype.nexus.repository.cache.NegativeCacheFacet");
      }
      catch (ClassNotFoundException e) {
        log.debug("NegativeCacheFacet not available, skipping negative cache invalidation");
        return;
      }

      Object negCacheFacet = repo.facet((Class) negCacheFacetClass);
      if (negCacheFacet != null) {
        try {
          java.lang.reflect.Method invalidateMethod = negCacheFacet.getClass().getMethod("invalidate", String.class);
          invalidateMethod.invoke(negCacheFacet, assetPath);
          log.debug("Invalidated negative cache for: {}", assetPath);
        }
        catch (NoSuchMethodException e) {
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
      log.debug("Negative cache invalidation skipped: {}", e.getMessage());
    }
  }

  // ========================================================================
  // Utility methods
  // ========================================================================

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

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }

  private String encodeAuth(final String userPass) {
    return java.util.Base64.getEncoder().encodeToString(userPass.getBytes());
  }

  private String readResponse(final HttpURLConnection conn) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    conn.disconnect();
    return sb.toString();
  }

  // ========================================================================
  // Simple JSON parsing (no external library dependency)
  // ========================================================================

  private String extractJsonValue(final String json, final String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return null;

    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return null;

    int valueStart = json.indexOf('"', colonIdx + 1);
    if (valueStart < 0) return null;

    int valueEnd = valueStart + 1;
    while (valueEnd < json.length()) {
      char c = json.charAt(valueEnd);
      if (c == '"' && json.charAt(valueEnd - 1) != '\\') {
        break;
      }
      valueEnd++;
    }

    if (valueEnd >= json.length()) return null;

    return json.substring(valueStart + 1, valueEnd)
        .replace("\\\"", "\"")
        .replace("\\\\", "\\");
  }

  private String extractJsonArray(final String json, final String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return null;

    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return null;

    int arrayStart = json.indexOf('[', colonIdx + 1);
    if (arrayStart < 0) return null;

    int arrayEnd = findMatchingBracket(json, arrayStart);
    if (arrayEnd < 0) return null;

    return json.substring(arrayStart + 1, arrayEnd);
  }

  private int findMatchingBracket(final String s, final int openPos) {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (!inString) {
        if (c == '[') depth++;
        else if (c == ']') {
          depth--;
          if (depth == 0) return i;
        }
      }
    }
    return -1;
  }

  private int findMatchingBrace(final String s, final int openPos) {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (!inString) {
        if (c == '{') depth++;
        else if (c == '}') {
          depth--;
          if (depth == 0) return i;
        }
      }
    }
    return -1;
  }
}
