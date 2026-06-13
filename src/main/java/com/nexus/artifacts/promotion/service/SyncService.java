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
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.hash.HashCode;

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

  /** Default admin credentials for internal API calls */
  private static final String DEFAULT_ADMIN_USERNAME = "admin";
  private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

  private volatile String adminUsername = DEFAULT_ADMIN_USERNAME;
  private volatile String adminPassword = DEFAULT_ADMIN_PASSWORD;

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
   * Update admin credentials from capability configuration.
   */
  public void updateAdminCredentials(final String username, final String password) {
    if (username != null && !username.isEmpty()) {
      this.adminUsername = username;
    }
    if (password != null && !password.isEmpty()) {
      this.adminPassword = password;
    }
    log.info("Admin credentials updated for sync service");
  }

  /**
   * Get the current admin auth string (username:password).
   */
  private String getAdminAuth() {
    return adminUsername + ":" + adminPassword;
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

          // Count skipped vs actually synced items
          long skippedCount = syncedFiles.stream().filter(f -> "skipped".equals(f.getStatus())).count();
          long syncedCount = syncedFiles.size() - skippedCount;
          taskInfo.setResult("Synced " + syncedCount + " items" +
              (skippedCount > 0 ? ", skipped " + skippedCount + " (unchanged)" : ""));

          log.info("Sync task completed: {} items synced, {} skipped from {}:{}",
              syncedCount, skippedCount, request.getRepositoryName(), request.getPath());

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
   * 2. For each file, compare MD5 of remote vs local cached asset
   * 3. If MD5 matches, skip (incremental sync); otherwise call ContentFacet.get(path)
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDirectory(final Repository repo, final String directoryPath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      log.info("Starting {} for {}:{}", isFullSync ? "full repository sync" : "directory sync",
          repo.getName(), isFullSync ? "/" : directoryPath);

      // Extract auth from proxy repo configuration
      String[] repoAuth = extractAuthFromRepo(repo);

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
          // Incremental check: compare MD5 of remote and local assets
          String remoteMd5 = getRemoteAssetMd5(repo, assetPath, repoAuth);
          String localMd5 = getLocalAssetMd5(repo.getName(), assetPath, repoAuth);

          detail.setRemoteMd5(remoteMd5);
          detail.setLocalMd5(localMd5);

          if (remoteMd5 != null && localMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
            log.info("Skipping sync (MD5 match): {}/{}, MD5={}", repo.getName(), assetPath, remoteMd5);
            detail.setStatus("skipped");
          }
          else {
            log.info("MD5 differs or missing, syncing: remoteMd5={}, localMd5={}, path={}",
                remoteMd5, localMd5, assetPath);
            syncAssetViaContentFacet(repo, assetPath, repoAuth);
            detail.setStatus("success");
          }
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
      // Extract auth from proxy repo configuration
      String[] repoAuth = extractAuthFromRepo(repo);

      SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
          filePath, determineType(filePath));

      try {
        // Incremental check: compare MD5 of remote and local assets
        String remoteMd5 = getRemoteAssetMd5(repo, filePath, repoAuth);
        String localMd5 = getLocalAssetMd5(repo.getName(), filePath, repoAuth);

        detail.setRemoteMd5(remoteMd5);
        detail.setLocalMd5(localMd5);

        if (remoteMd5 != null && localMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
          log.info("Skipping sync (MD5 match): {}/{}, MD5={}", repo.getName(), filePath, remoteMd5);
          detail.setStatus("skipped");
        }
        else {
          log.info("MD5 differs or missing, syncing: remoteMd5={}, localMd5={}, path={}",
              remoteMd5, localMd5, filePath);
          syncAssetViaContentFacet(repo, filePath, repoAuth);
          detail.setStatus("success");
        }
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
   * Sync a single asset by dispatching a GET request through the repository's view facet.
   *
   * Using ViewFacet.dispatch() routes the request through the full Nexus routing pipeline,
   * which properly sets up TokenMatcher.State and other context attributes that
   * ProxyFacet.get() requires. This avoids the "Missing: TokenMatcher$State" error.
   *
   * Flow:
   * 1. Check if asset exists in local cache
   *    - If exists: delete it to force fresh download from remote
   *    - If not exists: skip deletion, just fetch from remote
   * 2. Invalidate negative cache so previously 404'd assets can be retried
   * 3. Dispatch GET request through ViewFacet -> Nexus auto-fetches from remote if not cached
   */
  private void syncAssetViaContentFacet(final Repository repo, final String assetPath,
      final String[] repoAuth) throws Exception {
    log.info("Syncing asset {} via ViewFacet.dispatch()", assetPath);

    try {
      // Step 1: Delete cached asset if it exists, using internal StorageTx API
      deleteCachedAssetInternal(repo, assetPath);

      // Step 2: Invalidate negative cache so previously 404'd assets can be retried
      invalidateNegativeCache(repo, assetPath);

      // Step 3: Use ViewFacet.dispatch() - routes through full Nexus pipeline
      // This properly sets up TokenMatcher.State and other context attributes
      ViewFacet viewFacet = repo.facet(ViewFacet.class);
      if (viewFacet != null) {
        Request request = new Request.Builder()
            .action("GET")
            .path("/" + assetPath)
            .build();

        Response response = viewFacet.dispatch(request);

        if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
          log.info("Successfully synced asset {} from remote (HTTP {})",
              assetPath, response.getStatus().getCode());
        }
        else {
          log.warn("Failed to sync asset {}: HTTP {}", assetPath, response.getStatus().getCode());
        }
      }
      else {
        log.warn("Repository {} does not have ViewFacet, skipping sync", repo.getName());
      }
    }
    catch (Exception e) {
      log.error("Failed to sync asset {} via ViewFacet: {}", assetPath, e.getMessage());
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

      // Extract authentication config from proxy repo's httpclient settings
      String[] repoAuth = extractAuthFromRepo(repo);
      String authUsername = (repoAuth != null) ? repoAuth[0] : null;
      String authPassword = (repoAuth != null) ? repoAuth[1] : null;

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());

      // Strategy 1: Check if remote URL points to a local Nexus repo
      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        try {
          Repository remoteRepo = repositoryManager.get(remoteRepoName);
          if (remoteRepo != null) {
            log.info("Remote URL points to local Nexus repo: {}, listing via REST API (auth: {})",
                remoteRepoName, authUsername != null ? authUsername : "default");
            List<String> assets = listAssetsViaApi(remoteRepoName,
                isFullSync ? "" : directoryPath, authUsername, authPassword);
            log.info("Found {} assets from local repo {} in path {}",
                assets.size(), remoteRepoName, isFullSync ? "/" : directoryPath);
            return assets;
          }
        }
        catch (Exception ignored) {
          // Repo not found locally, fall through to HTTP
        }
      }

      // Strategy 2: Fall back to HTTP directory listing
      if (isFullSync) {
        // For full sync via HTTP, we need to recursively crawl the root directory
        log.info("Falling back to HTTP recursive directory listing for full repo sync: {}", remoteUrl);
        return listRemoteAssetsViaHttpRecursive("", remoteUrl, authUsername, authPassword);
      }
      log.info("Falling back to HTTP directory listing for remote: {}", remoteUrl);
      return listRemoteAssetsViaHttp(directoryPath, remoteUrl, authUsername, authPassword);

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
   * List assets from a local Nexus repository via Search API.
   * Strategy:
   * - If directoryPath is empty: full repo sync — list all assets without group/path filter
   * - Otherwise:
   *   1. First try with group filter (works for Maven2 format where group = groupId)
   *   2. If group filter returns 0 results (common for raw/hosted format where group doesn't
   *      match directory path), fall back to listing all assets and filtering by path prefix
   * Handles pagination via continuationToken.
   * Uses proxy-configured credentials if provided, falls back to default admin credentials.
   */
  private List<String> listAssetsViaApi(final String repoName, final String directoryPath,
      final String authUsername, final String authPassword) throws Exception {
    String effectiveAuth = (authUsername != null && authPassword != null)
        ? authUsername + ":" + authPassword : getAdminAuth();

    // Full repository sync — list all assets without any path filter
    boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
    if (isFullSync) {
      String allApiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/search/assets?repository=" + repoName;
      List<String> allAssets = listAssetsViaApiUrl(allApiUrl, effectiveAuth);
      log.info("Full repo sync: found {} total assets in repo {}", allAssets.size(), repoName);
      return allAssets;
    }

    String normalizedDir = directoryPath;
    if (normalizedDir.endsWith("/")) {
      normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
    }
    // Ensure normalizedDir starts without leading slash for consistent path matching
    String pathPrefix = normalizedDir;

    // Step 1: Try with group filter first (efficient for Maven2 repos)
    String groupApiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/search/assets?repository=" + repoName
        + "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
    List<String> assetsWithGroup = listAssetsViaApiUrl(groupApiUrl, effectiveAuth);

    // Check if group filter returned results — if yes, return them directly
    // but still filter by path prefix to ensure accuracy
    if (!assetsWithGroup.isEmpty()) {
      List<String> filtered = filterByPathPrefix(assetsWithGroup, pathPrefix);
      log.info("Search with group filter returned {} assets, {} matched path prefix '{}'",
          assetsWithGroup.size(), filtered.size(), pathPrefix);
      return filtered;
    }

    // Step 2: Group filter returned 0 results — fall back to listing all assets
    // and filtering by path prefix. This handles raw/hosted repos where the Search API's
    // group parameter doesn't correspond to the file system directory path.
    log.info("Group filter returned 0 results for '{}', falling back to path-prefix filter", normalizedDir);
    String allApiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/search/assets?repository=" + repoName;
    List<String> allAssets = listAssetsViaApiUrl(allApiUrl, effectiveAuth);
    List<String> filtered = filterByPathPrefix(allAssets, pathPrefix);

    log.info("Total assets in repo {}: {}, matched path prefix '{}': {}",
        repoName, allAssets.size(), pathPrefix, filtered.size());

    return filtered;
  }

  /**
   * List assets from a Search API URL, handling pagination.
   * Returns all asset paths found across all pages.
   */
  private List<String> listAssetsViaApiUrl(final String apiUrl, final String effectiveAuth) throws Exception {
    List<String> assetNames = new ArrayList<>();
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
      conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));

      if (conn.getResponseCode() != 200) {
        log.warn("Search API returned HTTP {} for {}", conn.getResponseCode(), url);
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

          if (path != null) {
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
   * Filter asset paths by a path prefix (directory).
   * Ensures that only assets directly under or within the specified directory are included.
   * For example, pathPrefix "2" matches "2/file.txt" and "2/sub/file.txt"
   * but not "20/file.txt" or "1/file.txt".
   */
  private List<String> filterByPathPrefix(final List<String> assetPaths, final String pathPrefix) {
    List<String> filtered = new ArrayList<>();
    // Normalize: remove leading slash from both for consistent matching
    String normalizedPrefix = pathPrefix.startsWith("/") ? pathPrefix.substring(1) : pathPrefix;

    for (String path : assetPaths) {
      // Remove leading slash from path for matching
      String normalizedPath = path.startsWith("/") ? path.substring(1) : path;

      // Path must start with the prefix followed by '/' or be exactly the prefix
      if (normalizedPath.equals(normalizedPrefix) ||
          normalizedPath.startsWith(normalizedPrefix + "/")) {
        filtered.add(path);
      }
    }
    return filtered;
  }

  /**
   * Recursively list all remote assets via HTTP directory listing.
   * Used for full-repository sync when the remote is not a local Nexus repo.
   * Crawls subdirectories recursively to find all files.
   */
  private List<String> listRemoteAssetsViaHttpRecursive(final String currentPath,
      final String remoteUrl, final String authUsername, final String authPassword) {
    List<String> allAssets = new ArrayList<>();

    try {
      String dirUrl = remoteUrl + currentPath;
      if (!dirUrl.endsWith("/")) {
        dirUrl += "/";
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(dirUrl).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");

      if (authUsername != null && authPassword != null) {
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(authUsername + ":" + authPassword));
      }

      if (conn.getResponseCode() != 200) {
        conn.disconnect();
        return allAssets;
      }

      String html = readResponse(conn);

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

        String assetPath = currentPath + (currentPath.endsWith("/") || currentPath.isEmpty() ? "" : "/") + href;

        if (href.endsWith("/")) {
          // Subdirectory — recurse into it
          List<String> subAssets = listRemoteAssetsViaHttpRecursive(assetPath, remoteUrl, authUsername, authPassword);
          allAssets.addAll(subAssets);
        }
        else {
          allAssets.add(assetPath);
        }
      }
    }
    catch (Exception e) {
      log.warn("Failed to list remote directory via HTTP for {}: {}", currentPath, e.getMessage());
    }

    return allAssets;
  }

  /**
   * List remote assets via HTTP directory listing (for external remotes).
   * Parses HTML directory index pages to extract file links.
   * Uses proxy-configured credentials if provided.
   */
  private List<String> listRemoteAssetsViaHttp(final String directoryPath,
      final String remoteUrl, final String authUsername, final String authPassword) {
    List<String> remoteAssets = new ArrayList<>();

    try {
      String dirUrl = remoteUrl + directoryPath;
      if (!dirUrl.endsWith("/")) {
        dirUrl += "/";
      }

      log.info("Fetching remote directory listing from: {} (auth: {})", dirUrl, authUsername != null ? authUsername : "no");

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
   * Delete a locally cached asset using Nexus internal StorageTx API.
   * If the asset exists in the repository's local cache, it is deleted to force
   * a fresh download from remote during the subsequent ViewFacet.dispatch().
   */
  private void deleteCachedAssetInternal(final Repository repo, final String assetPath) {
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);

      // Fallback: try with leading slash
      if (asset == null && !assetPath.startsWith("/")) {
        asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
      }

      if (asset != null) {
        log.info("Asset {} exists in cache, deleting to force fresh download", assetPath);
        tx.deleteAsset(asset);
        tx.commit();
      }
      else {
        log.info("Asset {} not in cache, will fetch fresh from remote", assetPath);
      }
    }
    catch (Exception e) {
      log.warn("Failed to delete cached asset {} via internal API: {}", assetPath, e.getMessage());
      if (tx != null) {
        try { tx.rollback(); } catch (Exception ex) { /* ignore */ }
      }
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception e) { /* ignore */ }
      }
    }
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
  // MD5 checksum retrieval via Nexus internal Java API
  // ========================================================================

  /**
   * Get the MD5 checksum of an asset from the remote (source) repository.
   * For proxy repos whose remote URL points to a local Nexus repo, this uses
   * the internal StorageTx API. Otherwise returns null (no MD5 available for
   * external HTTP remotes via directory listing).
   */
  private String getRemoteAssetMd5(final Repository proxyRepo, final String assetPath,
      final String[] repoAuth) {
    try {
      // Get remote URL from repository configuration
      org.sonatype.nexus.repository.config.Configuration config = proxyRepo.getConfiguration();
      if (config == null) return null;

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) return null;

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return null;

      // If remote URL points to a local Nexus repo, use internal API to get MD5
      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        Repository remoteRepo = repositoryManager.get(remoteRepoName);
        if (remoteRepo != null) {
          return getAssetMd5(remoteRepoName, assetPath);
        }
      }

      // For external HTTP remotes, we cannot easily get MD5 from directory listing
      // Return null so sync will always proceed (full sync behavior)
      return null;
    }
    catch (Exception e) {
      log.debug("Failed to get remote MD5 for {}/{}: {}", proxyRepo.getName(), assetPath, e.getMessage());
      return null;
    }
  }

  /**
   * Get the MD5 checksum of a locally cached asset in the proxy repository.
   * Uses Nexus internal StorageTx API. Returns null if the asset is not cached locally.
   */
  private String getLocalAssetMd5(final String repoName, final String assetPath,
      final String[] repoAuth) {
    return getAssetMd5(repoName, assetPath);
  }

  /**
   * Get the MD5 checksum of an asset using Nexus internal StorageTx API.
   * This is the most reliable way to get checksums — no HTTP roundtrip,
   * no authentication issues, reads directly from the blob store.
   * Returns null if the asset is not found or MD5 is not available.
   */
  private String getAssetMd5(final String repoName, final String assetPath) {
    try {
      Repository repo = repositoryManager.get(repoName);
      if (repo == null) {
        log.debug("Repository not found for MD5 lookup: {}", repoName);
        return null;
      }

      StorageTx tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      try {
        Bucket bucket = tx.findBucket(repo);
        Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);

        // Fallback: try with leading slash
        if (asset == null && !assetPath.startsWith("/")) {
          asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
        }

        if (asset == null) {
          log.debug("Asset not found for MD5 lookup: {}/{}", repoName, assetPath);
          return null;
        }

        // Method 1: Use Asset.getChecksum(HashAlgorithm.MD5) — Nexus 3.7+
        try {
          HashCode md5Hash = asset.getChecksum(HashAlgorithm.MD5);
          if (md5Hash != null) {
            String md5 = md5Hash.toString();
            if (!md5.isEmpty()) {
              log.debug("Found MD5 via Asset.getChecksum() for {}/{}: {}", repoName, assetPath, md5);
              return md5;
            }
          }
        }
        catch (Exception e) {
          log.debug("Asset.getChecksum(MD5) not available or failed for {}/{}: {}", repoName, assetPath, e.getMessage());
        }

        // Method 2: Try blob headers for Content-Hash-MD5
        try {
          if (asset.requireBlobRef() != null) {
            Blob blob = tx.requireBlob(asset.requireBlobRef());
            if (blob != null) {
              Map<String, String> headers = blob.getHeaders();
              String md5 = headers.get("Content-Hash-MD5");
              if (md5 == null || md5.isEmpty()) {
                md5 = headers.get("content-hash-md5");
              }
              if (md5 != null && !md5.isEmpty()) {
                log.debug("Found MD5 via blob headers for {}/{}: {}", repoName, assetPath, md5);
                return md5;
              }
            }
          }
        }
        catch (Exception e) {
          log.debug("Blob header MD5 lookup failed for {}/{}: {}", repoName, assetPath, e.getMessage());
        }
      }
      finally {
        tx.close();
      }
    }
    catch (Exception e) {
      log.debug("Failed to get MD5 via internal API for {}/{}: {}", repoName, assetPath, e.getMessage());
    }
    return null;
  }

  // ========================================================================
  // Utility methods
  // ========================================================================

  /**
   * Extract authentication credentials from a proxy repository's httpclient configuration.
   * Returns a String array [username, password], or null if no authentication is configured.
   */
  private String[] extractAuthFromRepo(final Repository repo) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        return null;
      }
      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("httpclient")) {
        return null;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> httpClientAttrs = attributes.get("httpclient");
      @SuppressWarnings("unchecked")
      Map<String, Object> authAttrs = (Map<String, Object>) httpClientAttrs.get("authentication");
      if (authAttrs == null) {
        return null;
      }
      String username = (String) authAttrs.get("username");
      String password = (String) authAttrs.get("password");
      if (username != null && password != null) {
        return new String[]{username, password};
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract auth from repo {}: {}", repo.getName(), e.getMessage());
    }
    return null;
  }

  /**
   * Build effective auth string for API calls.
   * Uses proxy-configured credentials if available, otherwise falls back to default admin credentials.
   */
  private String getEffectiveAuth(final String[] repoAuth) {
    if (repoAuth != null && repoAuth.length == 2 && repoAuth[0] != null && repoAuth[1] != null) {
      return repoAuth[0] + ":" + repoAuth[1];
    }
    return getAdminAuth();
  }

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
