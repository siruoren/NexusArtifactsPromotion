package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

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

  /** Nexus local base URLs for internal API calls - tries HTTPS first, then HTTP */
  private static final String LOCAL_NEXUS_BASE_HTTPS = "https://localhost:8081";
  private static final String LOCAL_NEXUS_BASE_HTTP = "http://localhost:8081";

  /** Cached working local base URL */
  private volatile String cachedLocalNexusBase = null;

  /** Maximum retry attempts for individual asset sync operations */
  private static final int SYNC_RETRY_ATTEMPTS = 2;

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;
  private final FileWriteLockManager writeLockManager;
  private final DockerService dockerService;

  private final Map<String, SyncTaskInfo> taskInfos = new ConcurrentHashMap<>();

  @Inject
  public SyncService(final RepositoryManager repositoryManager,
                      final TaskExecutorService taskExecutor,
                      final TaskCacheManager cacheManager,
                      final PermissionChecker permissionChecker,
                      final SecurityHelper securityHelper,
                      final FileWriteLockManager writeLockManager,
                      final DockerService dockerService)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
    this.securityHelper = securityHelper;
    this.writeLockManager = writeLockManager;
    this.dockerService = dockerService;
  }

  /**
   * Get the working local Nexus base URL.
   * Tries HTTPS first (for Nexus with SSL), then falls back to HTTP.
   * Result is cached after first successful connection.
   */
  private String getLocalNexusBase() {
    if (cachedLocalNexusBase != null) {
      return cachedLocalNexusBase;
    }

    // Try HTTPS first
    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTPS)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTPS;
      log.debug("Local Nexus base URL resolved to HTTPS: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    // Fall back to HTTP
    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTP)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
      log.debug("Local Nexus base URL resolved to HTTP: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    // Default to HTTP if both fail (Nexus might not be fully started yet)
    log.warn("Could not determine local Nexus base URL, defaulting to HTTP");
    cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
    return cachedLocalNexusBase;
  }

  /**
   * Test if a local Nexus URL is reachable.
   */
  private boolean testLocalConnection(final String baseUrl) {
    try {
      URL url = new URL(baseUrl + "/service/rest/v1/status");
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      int code = conn.getResponseCode();
      conn.disconnect();
      return code < 500;
    }
    catch (Exception e) {
      log.debug("Local connection test failed for {}: {}", baseUrl, e.getMessage());
      return false;
    }
  }

  /**
   * Execute sync of a remote repository path.
   */
  public String sync(final SyncRequest request) {
    request.validate();

    // Delegate Docker format syncs to DockerService
    if ("docker".equalsIgnoreCase(request.getFormat())) {
      log.info("Delegating Docker format sync to DockerService for repository: {}", request.getRepositoryName());
      com.nexus.artifacts.promotion.model.DockerImageRequest dockerRequest =
          new com.nexus.artifacts.promotion.model.DockerImageRequest();
      dockerRequest.setSourceRepository(request.getRepositoryName());
      dockerRequest.setFormat(request.getFormat());
      // tags=null means all tags (isAllTags() returns true)
      dockerRequest.setTags(null);
      // Extract image name from path if provided
      if (request.getPath() != null && !request.getPath().trim().isEmpty()) {
        String path = request.getPath();
        if (path.startsWith("v2/")) {
          path = path.substring(3);
        }
        // Remove trailing /manifests/* or /blobs/*
        int manifestsIdx = path.indexOf("/manifests/");
        int blobsIdx = path.indexOf("/blobs/");
        if (manifestsIdx > 0) {
          // Path like v2/projectmanifests/8.3.2.891 -> image=project, tag=8.3.2.891
          String image = path.substring(0, manifestsIdx);
          String tag = path.substring(manifestsIdx + "/manifests/".length());
          dockerRequest.setImage(image);
          dockerRequest.setTags(Collections.singletonList(tag));
          dockerRequest.setAllImages(false);
        }
        else if (blobsIdx > 0) {
          path = path.substring(0, blobsIdx);
          dockerRequest.setImage(path);
          dockerRequest.setAllImages(false);
        }
        else {
          // No /manifests/ or /blobs/ - use the same image discovery logic as promotion
          // (listDockerImages + prefix filtering) to resolve the path.
          // This handles all cases:
          //   a) directory prefix with sub-images (e.g. project/8.3.2.891 -> project/8.3.2.891/app1, project/8.3.2.891/app2)
          //   b) image name with tags (e.g. project/8.3.2.891 has tags directly)
          //   c) specific image:tag (e.g. image=project, tag=8.3.2.891)
          int lastSlash = path.lastIndexOf('/');
          if (lastSlash > 0) {
            // Use listDockerImagesByPrefix to find matching images (same strategy chain as promotion)
            Map<String, List<String>> prefixImages = dockerService.listDockerImagesByPrefix(request.getRepositoryName(), path);
            if (!prefixImages.isEmpty()) {
              // Found images matching the path prefix
              if (prefixImages.size() == 1 && prefixImages.containsKey(path)) {
                // Only one match and it's the exact path -> single image with tags
                dockerRequest.setImage(path);
                dockerRequest.setTags(null);
                dockerRequest.setAllImages(false);
                log.debug("Parsed Docker path '{}' as image='{}' (found {} tags), will sync all tags from remote",
                    path, path, prefixImages.get(path).size());
              }
              else {
                // Multiple sub-images or the path is a directory prefix
                dockerRequest.setImagePrefix(path);
                dockerRequest.setAllImages(false);
                log.debug("Parsed Docker path '{}' as directory prefix (found {} images), will sync all from remote",
                    path, prefixImages.size());
              }
            }
            else {
              // No images found for the full path - try splitting as image:tag
              String parentImage = path.substring(0, lastSlash);
              String tagCandidate = path.substring(lastSlash + 1);
              dockerRequest.setImage(parentImage);
              dockerRequest.setTags(Collections.singletonList(tagCandidate));
              dockerRequest.setAllImages(false);
            log.debug("Parsed Docker path '{}' as image='{}', tag='{}' (no images found for full path)",
                  path, parentImage, tagCandidate);
            }
          }
          else {
            // No slash - just an image name, sync all tags
            dockerRequest.setImage(path);
            dockerRequest.setTags(null);
            dockerRequest.setAllImages(false);
          log.debug("Parsed Docker path '{}' as image name, will sync all tags from remote", path);
          }
        }
      }
      else {
        dockerRequest.setAllImages(true);
      }
      return dockerService.syncDockerImage(dockerRequest);
    }

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
            syncedFiles = syncDirectory(repo, request.getPath(), null);
          }
          else {
            syncedFiles = syncFile(repo, request.getPath(), null);
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
          // If the thread was interrupted (manual cancellation), use CANCELLED status
          boolean isCancelled = Thread.currentThread().isInterrupted()
              || (e instanceof InterruptedException)
              || (e instanceof RuntimeException && e.getCause() instanceof InterruptedException);
          if (isCancelled) {
            Thread.currentThread().interrupt();
          }
          log.error(isCancelled ? "Sync task cancelled: {}" : "Sync task failed: {}", e.getMessage(), isCancelled ? null : e);
          taskInfo.setStatus(isCancelled ? TaskStatus.CANCELLED : TaskStatus.FAILED);
          taskInfo.setErrorMessage(isCancelled ? "Task cancelled" : sanitizeErrorMessage(e.getMessage()));
          taskInfo.setEndTime(System.currentTimeMillis());
          taskInfo.setResult(isCancelled ? "Cancelled" : "Failed: " + sanitizeErrorMessage(e.getMessage()));
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
        request.getRepositoryName(), request.getPath(), preTaskId, username);

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
   * Execute sync of a remote repository path synchronously (for scheduled tasks).
   * Unlike {@link #sync(SyncRequest)}, this method:
   * - Skips permission checks (scheduled tasks are created by administrators)
   * - Executes synchronously in the calling thread (no thread pool)
   * - Returns the full SyncTaskInfo with results
   *
   * @param request the sync request
   * @return SyncTaskInfo with sync results
   */
  public SyncTaskInfo syncScheduled(final SyncRequest request) {
    return syncScheduled(request, null);
  }

  /**
   * Execute a scheduled sync task with support for Nexus task cancellation.
   * 
   * @param request the sync request
   * @param task the Nexus task instance (may be null for direct API calls)
   * @return SyncTaskInfo with sync results
   */
  public SyncTaskInfo syncScheduled(final SyncRequest request, 
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {
    request.validate();

    // Delegate Docker format syncs to DockerService (synchronous, asset-based approach)
    if ("docker".equalsIgnoreCase(request.getFormat())) {
      log.info("Delegating Docker scheduled sync to DockerService for repository: {}", request.getRepositoryName());
      return dockerService.syncDockerImageScheduled(request);
    }

    // No permission check for scheduled tasks — tasks are created by administrators

    Repository repo = repositoryManager.get(request.getRepositoryName());
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + request.getRepositoryName());
    }

    // Verify it's a proxy repository
    if (!"proxy".equals(repo.getType().getValue())) {
      throw new IllegalArgumentException("Repository is not a proxy type: " + request.getRepositoryName());
    }

    String taskId = "scheduled-sync-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    SyncTaskInfo taskInfo = new SyncTaskInfo();
    taskInfo.setTaskId(taskId);
    taskInfo.setSourceRepository(request.getRepositoryName());
    taskInfo.setPath(request.getPath());
    taskInfo.setDirectory(request.isDirectory());
    taskInfo.setFormat(request.getFormat());
    taskInfo.setUsername("system");
    taskInfo.setStartTime(System.currentTimeMillis());
    taskInfo.setStatus(TaskStatus.RUNNING);
    taskInfo.setTargetRepository(repo.getName());

    // Store initial state
    taskInfos.put(taskId, taskInfo);

    try {
      cacheManager.createTaskCache(taskId);

      boolean isFullSync = (request.getPath() == null || request.getPath().trim().isEmpty());
      log.info("Starting scheduled {} for {}:{}",
          isFullSync ? "full repository sync" : "directory sync",
          request.getRepositoryName(),
          isFullSync ? "/" : request.getPath());

      // Execute sync using ContentFacet
      List<SyncTaskInfo.SyncFileDetail> syncedFiles;
      if (request.isDirectory()) {
        syncedFiles = syncDirectory(repo, request.getPath(), task);
      }
      else {
        syncedFiles = syncFile(repo, request.getPath(), task);
      }

      // Check if task was cancelled during sync
      boolean taskCancelled = Thread.currentThread().isInterrupted() || (task != null && task.isCanceled());

      taskInfo.setFileDetails(syncedFiles);
      
      // Set status based on whether task was cancelled
      if (taskCancelled) {
        Thread.currentThread().interrupt(); // Reset interrupted flag
        taskInfo.setStatus(TaskStatus.CANCELLED);
        taskInfo.setResult("Cancelled: " + syncedFiles.size() + " items synced");
      } else {
        taskInfo.setStatus(TaskStatus.COMPLETED);
        taskInfo.setEndTime(System.currentTimeMillis());

        // Count skipped vs actually synced items
        long skippedCount = syncedFiles.stream().filter(f -> "skipped".equals(f.getStatus())).count();
        long syncedCount = syncedFiles.size() - skippedCount;
        taskInfo.setResult("Synced " + syncedCount + " items" +
            (skippedCount > 0 ? ", skipped " + skippedCount + " (unchanged)" : ""));
      }

      log.info("Scheduled sync task {}: {} items synced from {}:{}",
          taskCancelled ? "cancelled" : "completed", syncedFiles.size(), request.getRepositoryName(),
          isFullSync ? "/" : request.getPath());
    }
    catch (Exception e) {
      boolean isCancelled = Thread.currentThread().isInterrupted()
          || (e instanceof InterruptedException)
          || (e instanceof RuntimeException && e.getCause() instanceof InterruptedException);
      if (isCancelled) {
        Thread.currentThread().interrupt();
      }
      log.error(isCancelled ? "Scheduled sync task cancelled: {}" : "Scheduled sync task failed: {}", e.getMessage(), isCancelled ? null : e);
      taskInfo.setStatus(isCancelled ? TaskStatus.CANCELLED : TaskStatus.FAILED);
      taskInfo.setErrorMessage(isCancelled ? "Task cancelled" : sanitizeErrorMessage(e.getMessage()));
      taskInfo.setEndTime(System.currentTimeMillis());
      taskInfo.setResult(isCancelled ? "Cancelled" : "Failed: " + sanitizeErrorMessage(e.getMessage()));
    }
    finally {
      // Update stored info with final state (ensure this happens even if exception occurs)
      taskInfos.put(taskId, taskInfo);
    }

    return taskInfo;
  }

  /**
   * Get sync task info by ID.
   */
  public SyncTaskInfo getTaskInfo(final String taskId) {
    cleanupExpiredTaskInfos();
    SyncTaskInfo info = taskInfos.get(taskId);
    if (info != null) {
      // Only override status from TaskExecutor if info is not already in a terminal state
      if (info.getStatus() != TaskStatus.FAILED && info.getStatus() != TaskStatus.COMPLETED && info.getStatus() != TaskStatus.CANCELLED) {
        TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
        if (status != null) {
          info.setStatus(status);
        }
      }
      if (info.getStatus() == TaskStatus.COMPLETED || info.getStatus() == TaskStatus.FAILED || info.getStatus() == TaskStatus.CANCELLED || info.getStatus() == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(taskId);
      }
      return info;
    }

    // Also check Docker sync tasks
    if (taskId != null && taskId.startsWith("docker-sync-")) {
      try {
        info = dockerService.getSyncTaskInfo(taskId);
        if (info != null) {
          return info;
        }
      }
      catch (Exception e) {
        log.warn("Failed to retrieve Docker sync task info for {}: {}", taskId, e.getMessage());
      }
    }

    return null;
  }

  /**
   * Cancel a sync task by updating its task info status to CANCELLED.
   */
  public void cancelSyncTask(final String taskId) {
    SyncTaskInfo info = taskInfos.get(taskId);
    if (info != null && info.getStatus() != TaskStatus.CANCELLED) {
      log.info("Updating sync task {} info status from {} to CANCELLED", taskId, info.getStatus());
      info.setStatus(TaskStatus.CANCELLED);
      info.setEndTime(System.currentTimeMillis());
      info.setResult("Task cancelled");
    }
  }

  /**
   * Get all sync tasks (for queue display).
   * Returns at most maxTaskQueueRecords entries (newest first).
   */
  public List<SyncTaskInfo> getAllSyncTasks() {
    cleanupExpiredTaskInfos();
    List<SyncTaskInfo> tasks = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      if (info.getTaskType() == null) {
        info.setTaskType("sync");
      }
      TaskStatus status = taskExecutor.getSyncTaskStatus(entry.getKey());
      if (status != null) {
        info.setStatus(status);
      }
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED || status == TaskStatus.MIGRATED) {
        taskExecutor.cleanupSyncTaskHandle(entry.getKey());
      }
      tasks.add(info);
    }

    // Merge Docker sync tasks into the unified queue view
    try {
      List<SyncTaskInfo> dockerTasks = dockerService.getAllSyncTaskInfos();
      tasks.addAll(dockerTasks);
    }
    catch (Exception e) {
      log.warn("Failed to retrieve Docker sync task infos: {}", e.getMessage());
    }

    tasks.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));
    int maxRecords = taskExecutor.getMaxTaskQueueRecords();
    if (tasks.size() > maxRecords) {
      tasks = tasks.subList(0, maxRecords);
    }
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
   * Clean up task info entries that have been completed for longer than the configured TTL.
   */
  private void cleanupExpiredTaskInfos() {
    long ttlMs = taskExecutor.getTaskLogTtlMs();
    long now = System.currentTimeMillis();
    List<String> expiredTaskIds = new ArrayList<>();

    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = info.getStatus();
      if ((status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED || status == TaskStatus.MIGRATED)
          && info.getEndTime() > 0
          && (now - info.getEndTime()) > ttlMs) {
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
   * 2. For each file, call ContentFacet.get(path) to sync from remote
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDirectory(final Repository repo, final String directoryPath,
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      log.info("Starting {} for {}:{}",
          isFullSync ? "full repository sync" : "directory sync",
          repo.getName(), isFullSync ? "/" : directoryPath);

      // Extract auth from proxy repo configuration
      String[] repoAuth = extractAuthFromRepo(repo);

      // Step 1: Get remote file list
      List<String> remoteAssets = listRemoteAssets(repo, directoryPath);
      log.info("Found {} remote assets to sync in {}:{}",
          remoteAssets.size(), repo.getName(), directoryPath);

      // Step 2: Sync each asset using ContentFacet.get()
      for (String rawAssetPath : remoteAssets) {
        // Check for task cancellation/interruption
        // Support both Nexus task cancellation (task.isCanceled()) and thread interruption
        if (Thread.currentThread().isInterrupted() || (task != null && task.isCanceled())) {
          log.warn("Sync task cancelled, stopping directory sync for {}:{}", repo.getName(), directoryPath);
          // Filter to only return successfully synced files to avoid showing 404 files
          List<SyncTaskInfo.SyncFileDetail> successfulFiles = new ArrayList<>();
          for (SyncTaskInfo.SyncFileDetail detail : details) {
            if ("success".equals(detail.getStatus())) {
              successfulFiles.add(detail);
            }
          }
          log.info("Sync task cancelled, returning {} successfully synced files out of {}", 
              successfulFiles.size(), details.size());
          return successfulFiles;
        }

        // Normalize: strip leading slash for consistent path matching
        String assetPath = rawAssetPath.startsWith("/") ? rawAssetPath.substring(1) : rawAssetPath;

        // Skip directory entries
        if (assetPath.endsWith("/")) {
          continue;
        }

        SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
            assetPath, determineType(assetPath));

        try {
          // Sync the asset
          log.info("Syncing asset: {}/{}", repo.getName(), assetPath);
          RetryableOperation.executeRun("sync asset " + assetPath,
              () -> syncAssetViaContentFacet(repo, assetPath, repoAuth),
              SYNC_RETRY_ATTEMPTS);
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
  private List<SyncTaskInfo.SyncFileDetail> syncFile(final Repository repo, final String filePath,
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      // Check for task cancellation/interruption at start
      if (Thread.currentThread().isInterrupted() || (task != null && task.isCanceled())) {
        log.warn("Sync task cancelled, skipping file sync for {}:{}", repo.getName(), filePath);
        return details; // Return empty list when cancelled
      }

      // Extract auth from proxy repo configuration
      String[] repoAuth = extractAuthFromRepo(repo);

      SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
          filePath, determineType(filePath));

      try {
        // Sync the file
        log.info("Syncing file: {}/{}", repo.getName(), filePath);
        syncAssetViaContentFacet(repo, filePath, repoAuth);
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
   * Get MD5 of a locally cached asset by its path.
   */
  private String getLocalAssetMd5(final Repository repo, final String assetPath) {
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();

      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) return null;

      // Try to find the asset by name in the bucket
      Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);
      if (asset == null) {
        // Try with leading slash
        asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
      }
      if (asset == null) return null;

      return getAssetMd5(asset);
    }
    catch (Exception e) {
      log.debug("Failed to get local MD5 for {}/{}: {}", repo.getName(), assetPath, e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return null;
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
    // For file extensions that are essentially zip archives but have specific MIME types,
    // ViewFacet.dispatch() will store the specific type (e.g. application/x-xpinstall for .xpi),
    // but Nexus's content validation on download will detect application/zip and reject it.
    // Bypass ViewFacet.dispatch() for these files and use direct HTTP sync instead.
    if (isZipBasedExtension(assetPath)) {
      log.debug("Asset {} has zip-based extension, using direct HTTP sync to avoid Content-Type mismatch", assetPath);
      boolean result = syncAssetViaDirectHttp(repo, assetPath, repoAuth);
      if (!result) {
        throw new RuntimeException("Failed to sync asset " + assetPath + " via direct HTTP (zip-based extension)");
      }
      return;
    }

    log.debug("Syncing asset {} via ViewFacet.dispatch()", assetPath);

    // Use file write lock to prevent concurrent sync of the same asset in the same repo
    writeLockManager.executeWithFileLockVoid(repo.getName(), assetPath, () -> {
      try {
        // Step 1: Delete cached asset if it exists, using internal StorageTx API
        deleteCachedAssetInternal(repo, assetPath);

        // Step 2: Invalidate negative cache so previously 404'd assets can be retried
        invalidateNegativeCache(repo, assetPath);

        // Step 3: Use ViewFacet.dispatch() - routes through full Nexus pipeline
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
            int httpCode = response.getStatus().getCode();
            // Attempt direct HTTP download fallback for Content-Type mismatch errors
            // Nexus returns 404 when InvalidContentException prevents caching
            if (httpCode == 404 || httpCode == 400) {
              log.warn("ViewFacet.dispatch() returned HTTP {} for {}, attempting direct HTTP download fallback", httpCode, assetPath);
              boolean fallbackResult = syncAssetViaDirectHttp(repo, assetPath, repoAuth);
              if (fallbackResult) {
                log.info("Successfully synced asset {} via direct HTTP fallback (bypassed Content-Type check)", assetPath);
                return;
              }
              log.warn("Direct HTTP fallback also failed for {}", assetPath);
            }
            throw new RuntimeException("Failed to sync asset " + assetPath + ": HTTP " + httpCode);
          }
        }
        else {
          log.warn("Repository {} does not have ViewFacet, skipping sync", repo.getName());
        }
      }
      catch (Exception e) {
        // Check if the exception is caused by Content-Type mismatch
        String errorMsg = e.getMessage();
        if (errorMsg != null && errorMsg.contains("InvalidContentException")) {
          log.warn("Content-Type mismatch for {}, attempting direct HTTP download fallback", assetPath);
          boolean fallbackResult = syncAssetViaDirectHttp(repo, assetPath, repoAuth);
          if (fallbackResult) {
          log.debug("Successfully synced asset {} via direct HTTP fallback (bypassed Content-Type check)", assetPath);
          return;
        }
        log.warn("Direct HTTP fallback also failed for {} after InvalidContentException", assetPath);
        }
        log.error("Failed to sync asset {} via ViewFacet: {}", assetPath, e.getMessage());
        throw new RuntimeException("Failed to sync from remote: " + e.getMessage(), e);
      }
    });
  }

  /**
   * Sync an asset by directly downloading from remote via HTTP and storing it
   * using Nexus internal StorageTx API. This bypasses the ViewFacet pipeline
   * and Content-Type validation, which is needed when the remote server returns
   * a Content-Type that doesn't match the file extension (e.g. .xpi files
   * returning application/zip instead of application/x-xpinstall).
   *
   * @return true if the asset was successfully synced, false otherwise
   */
  private boolean syncAssetViaDirectHttp(final Repository repo, final String assetPath,
      final String[] repoAuth) throws Exception {
    // Get remote URL from repository configuration
    org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
    if (config == null) {
      log.warn("syncAssetViaDirectHttp: repo config is null for {}", repo.getName());
      return false;
    }

    Map<String, Map<String, Object>> attributes = config.getAttributes();
    if (attributes == null || !attributes.containsKey("proxy")) {
      log.warn("syncAssetViaDirectHttp: no proxy attributes for {}", repo.getName());
      return false;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> proxyAttrs = attributes.get("proxy");
    String remoteUrl = (String) proxyAttrs.get("remoteUrl");
    if (remoteUrl == null || remoteUrl.isEmpty()) {
      log.warn("syncAssetViaDirectHttp: remoteUrl is empty for {}", repo.getName());
      return false;
    }

    if (!remoteUrl.endsWith("/")) {
      remoteUrl += "/";
    }

    // Download the file from remote via HTTP
    String fileUrl = remoteUrl + assetPath;
    log.info("Direct HTTP download: {}", fileUrl);

    HttpURLConnection conn = null;
    java.nio.file.Path tempFile = null;
    try {
      java.net.URL url = new java.net.URL(fileUrl);
      conn = (HttpURLConnection) url.openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(30_000);
      conn.setReadTimeout(60_000);

      if (repoAuth != null && repoAuth[0] != null && repoAuth[1] != null) {
        String auth = encodeAuth(repoAuth[0] + ":" + repoAuth[1]);
        conn.setRequestProperty("Authorization", "Basic " + auth);
      }

      int responseCode = conn.getResponseCode();
      log.info("Direct HTTP download: {} returned HTTP {}", fileUrl, responseCode);
      if (responseCode != 200) {
        log.warn("Direct HTTP download returned HTTP {} for {}", responseCode, fileUrl);
        return false;
      }

      // Get content type from remote response
      String contentType = conn.getContentType();
      if (contentType == null || contentType.isEmpty()) {
        contentType = "application/octet-stream";
      }
      // Strip charset and other parameters
      int semicolon = contentType.indexOf(';');
      if (semicolon > 0) {
        contentType = contentType.substring(0, semicolon).trim();
      }

      // Use extension-based content type to match user upload behavior.
      // User uploads can specify content type based on file extension (e.g. application/x-xpinstall for .xpi).
      // Sync should behave the same way: use extension-specific content type regardless of what remote returns.
      // This ensures consistency between user-uploaded and synced files.
      String effectiveContentType = contentType;
      if (isZipBasedExtension(assetPath)) {
        String specificType = getContentTypeByExtension(assetPath);
        if (specificType != null) {
          effectiveContentType = specificType;
          log.debug("Using extension-specific content type {} for {} (remote returned {})", specificType, assetPath, contentType);
        }
      } else if ("application/octet-stream".equals(contentType)) {
        String specificType = getContentTypeByExtension(assetPath);
        if (specificType != null) {
          effectiveContentType = specificType;
          log.debug("Overriding content type from {} to {} for {}", contentType, specificType, assetPath);
        }
      }

      // Save the downloaded file to a temp file
      tempFile = java.nio.file.Files.createTempFile("nexus-sync-", ".tmp");
      try (java.io.InputStream is = conn.getInputStream();
           java.io.OutputStream os = java.nio.file.Files.newOutputStream(tempFile)) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
      }
      long fileSize = java.nio.file.Files.size(tempFile);
      log.info("Direct HTTP download: saved {} bytes to temp file for {}", fileSize, assetPath);

      if (fileSize == 0) {
        log.warn("Direct HTTP download: empty file for {}", assetPath);
        return false;
      }

      // Store the downloaded content directly via StorageTx
      // This bypasses the ViewFacet pipeline and Content-Type validation
      StorageTx tx = null;
      try {
        tx = repo.facet(StorageFacet.class).txSupplier().get();
        tx.begin();

        Bucket bucket = tx.findBucket(repo);
        if (bucket == null) {
          log.warn("Bucket not found for repository: {}", repo.getName());
          return false;
        }

        String assetName = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;

        // Determine group and component name first
        String format = repo.getFormat() != null ? repo.getFormat().getValue() : "";
        String group = extractGroupFromPath(assetPath, format);
        String componentName = extractComponentNameFromPath(assetPath);

        // Delete existing asset if present
        Asset existingAsset = tx.findAssetWithProperty("name", assetName, bucket);
        if (existingAsset == null && !assetPath.startsWith("/")) {
          existingAsset = tx.findAssetWithProperty("name", assetPath, bucket);
        }
        if (existingAsset != null) {
          // For zip-based extensions, check if the existing asset has mismatched content type
          // If so, force delete it to fix the content type
          boolean forceDelete = false;
          if (isZipBasedExtension(assetPath)) {
            String existingContentType = existingAsset.contentType();
            if (existingContentType != null && !existingContentType.isEmpty()) {
              boolean isZipType = "application/zip".equals(existingContentType)
                  || "application/x-zip-compressed".equals(existingContentType);
              if (!isZipType) {
                log.info("Direct HTTP sync: force deleting asset {} with mismatched content type {} (should be zip-based)", 
                    assetPath, existingContentType);
                forceDelete = true;
              }
            }
          }
          
          if (forceDelete) {
            // When force deleting due to content type mismatch, also delete the component
            // to avoid ORecordDuplicatedException when recreating the asset
            // Find the component by group and name, then delete it
            Component existingComponent = tx.findComponentWithProperty("name", componentName, bucket);
            if (existingComponent != null && group.equals(existingComponent.group())) {
              log.debug("Direct HTTP sync: deleting component {} along with asset {}", existingComponent.name(), assetPath);
              tx.deleteComponent(existingComponent);
            } else {
              tx.deleteAsset(existingAsset);
            }
            // Commit delete to ensure index is updated before creating new asset
            tx.commit();
            tx.begin();
            // Re-find bucket after re-opening transaction
            bucket = tx.findBucket(repo);
          } else {
            log.debug("Direct HTTP sync: deleting existing asset {} before re-sync", assetPath);
            tx.deleteAsset(existingAsset);
          }
        }

        // Find or create Component (avoid ORecordDuplicatedException by reusing existing)
        Component component;
        if (tx.componentExists(group, componentName, null, repo)) {
          // Component already exists - find and reuse it
          Component existingComp = tx.findComponentWithProperty("name", componentName, bucket);
          if (existingComp != null && group.equals(existingComp.group())) {
            component = existingComp;
            log.debug("Direct HTTP sync: reusing existing component group='{}' name='{}' for {}", group, componentName, assetPath);
          } else {
            // Fallback: browse and match
            component = null;
            for (Component c : tx.browseComponents(bucket)) {
              if (group.equals(c.group()) && componentName.equals(c.name())) {
                component = c;
                break;
              }
            }
            if (component == null) {
              component = tx.createComponent(bucket, repo.getFormat())
                  .group(group)
                  .name(componentName);
              tx.saveComponent(component);
              log.debug("Direct HTTP sync: created component group='{}' name='{}' for {}", group, componentName, assetPath);
            } else {
              log.debug("Direct HTTP sync: reusing existing component group='{}' name='{}' for {}", group, componentName, assetPath);
            }
          }
        } else {
          component = tx.createComponent(bucket, repo.getFormat())
              .group(group)
              .name(componentName);
          tx.saveComponent(component);
          log.debug("Direct HTTP sync: created component group='{}' name='{}' for {}", group, componentName, assetPath);
        }

        // Create Asset under the Component
        Asset asset = tx.createAsset(bucket, component);
        asset.name(assetName);

        // Set blob using InputStreamSupplier-based setBlob (avoids NPE in Path-based setBlob)
        // Signature: setBlob(Asset, String, InputStreamSupplier, Iterable<HashAlgorithm>, Map<String,String>, String, boolean)
        final java.nio.file.Path blobTempFile = tempFile;
        org.sonatype.nexus.common.io.InputStreamSupplier streamSupplier = () -> java.nio.file.Files.newInputStream(blobTempFile);

        java.util.List<org.sonatype.nexus.common.hash.HashAlgorithm> hashAlgos = java.util.Arrays.asList(
            org.sonatype.nexus.common.hash.HashAlgorithm.MD5,
            org.sonatype.nexus.common.hash.HashAlgorithm.SHA1,
            org.sonatype.nexus.common.hash.HashAlgorithm.SHA256
        );

        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        tx.setBlob(asset, assetName, streamSupplier, hashAlgos, headers, effectiveContentType, true);
        tx.saveAsset(asset);
        tx.commit();
        log.info("Direct HTTP sync: stored asset {} with content type {} ({} bytes)", assetPath, effectiveContentType, fileSize);
        return true;
      }
      catch (Exception e) {
        log.error("Direct HTTP sync: StorageTx failed for {}: [{}] {}", assetPath, e.getClass().getSimpleName(), e.getMessage(), e);
        return false;
      }
      finally {
        if (tx != null) {
          try { tx.close(); } catch (Exception ignored) { }
        }
      }
    }
    catch (Exception e) {
      log.error("Direct HTTP sync: download/store failed for {}: [{}] {}", assetPath, e.getClass().getSimpleName(), e.getMessage(), e);
      return false;
    }
    finally {
      if (tempFile != null) {
        try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception ignored) { }
      }
      if (conn != null) {
        try { conn.disconnect(); } catch (Exception ignored) { }
      }
    }
  }

  /**
   * Check if the file extension indicates a format that is essentially a zip archive
   * but has a specific MIME type. These files cause Content-Type mismatch errors
   * when synced via ViewFacet.dispatch() because Nexus stores the specific MIME type
   * (e.g. application/x-xpinstall for .xpi) but detects application/zip on download.
   */
  private boolean isZipBasedExtension(final String assetPath) {
    if (assetPath == null) return false;
    String lower = assetPath.toLowerCase();
    return lower.endsWith(".xpi")
        || lower.endsWith(".crx")
        || lower.endsWith(".apk")
        || lower.endsWith(".docx")
        || lower.endsWith(".xlsx")
        || lower.endsWith(".pptx")
        || lower.endsWith(".odt")
        || lower.endsWith(".ods");
  }

  /**
   * Check if a cached asset has a content_type that will cause InvalidContentException on download.
   * This happens when the asset was stored with a specific MIME type (e.g. application/x-xpinstall)
   * but the actual content is detected as application/zip by Nexus's content validation.
   */
  private boolean hasMismatchedContentType(final Repository repo, final String assetPath) {
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();

      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) return false;

      String assetName = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
      Asset asset = tx.findAssetWithProperty("name", assetName, bucket);
      if (asset == null && !assetPath.startsWith("/")) {
        asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
      }
      if (asset == null) return false;

      String contentType = asset.contentType();
      if (contentType != null && !contentType.isEmpty()) {
        // If the stored content_type is NOT application/zip but the file is a zip-based extension,
        // it means the content_type was set to a specific type (e.g. application/x-xpinstall)
        // which will conflict with Nexus's content detection on download.
        boolean isZipType = "application/zip".equals(contentType)
            || "application/x-zip-compressed".equals(contentType);
        return !isZipType;
      }
    }
    catch (Exception e) {
      log.debug("Failed to check content type for {}: {}", assetPath, e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return false;
  }

  /**
   * Get a more specific content type based on file extension.
   * Used when the remote server returns a generic content type like application/zip
   * but the file extension implies a more specific type.
   */
  private String getContentTypeByExtension(final String assetPath) {
    if (assetPath == null) return null;
    String lower = assetPath.toLowerCase();
    if (lower.endsWith(".xpi")) return "application/x-xpinstall";
    if (lower.endsWith(".crx")) return "application/x-chrome-extension";
    if (lower.endsWith(".jar")) return "application/java-archive";
    if (lower.endsWith(".war")) return "application/java-archive";
    if (lower.endsWith(".ear")) return "application/java-archive";
    if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
    if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    if (lower.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
    if (lower.endsWith(".ods")) return "application/vnd.oasis.opendocument.spreadsheet";
    return null;
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
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());

      // Get remote URL from repository configuration
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        log.warn("No configuration found for repository {}", repo.getName());
        return listCachedAssetsInternal(repo, directoryPath);
      }

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) {
        log.warn("No proxy configuration found for repository {}", repo.getName());
        return listCachedAssetsInternal(repo, directoryPath);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        log.warn("No remote URL configured for proxy repository {}", repo.getName());
        return listCachedAssetsInternal(repo, directoryPath);
      }

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      // Extract authentication config from proxy repo's httpclient settings
      String[] repoAuth = extractAuthFromRepo(repo);
      String authUsername = (repoAuth != null) ? repoAuth[0] : null;
      String authPassword = (repoAuth != null) ? repoAuth[1] : null;

      // Strategy 1: Check if remote URL points to a local Nexus repo
      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        try {
          Repository remoteRepo = repositoryManager.get(remoteRepoName);
          if (remoteRepo != null) {
            log.debug("Remote URL points to local Nexus repo: {}, listing via REST API (auth: {})",
                remoteRepoName, authUsername != null ? authUsername : "default");
            List<String> assets = listAssetsViaApi(remoteRepoName,
                isFullSync ? "" : directoryPath, authUsername, authPassword);
            log.debug("Found {} assets from local repo {} in path {}",
                assets.size(), remoteRepoName, isFullSync ? "/" : directoryPath);
            return assets;
          }
        }
        catch (Exception ignored) {
          // Repo not found locally, fall through
        }
      }

      // Strategy 2: Try remote Nexus Search API (for external Nexus instances)
      // This can discover ALL remote assets, including those not yet cached locally.
      List<String> remoteApiAssets = listAssetsViaRemoteNexusApi(remoteUrl, repo.getName(),
          directoryPath, authUsername, authPassword);
      if (!remoteApiAssets.isEmpty()) {
        log.debug("Found {} assets via remote Nexus Search API for repo {} in path '{}'",
            remoteApiAssets.size(), repo.getName(), isFullSync ? "/" : directoryPath);
        return remoteApiAssets;
      }

      // Strategy 3: Fall back to HTTP directory listing
      if (isFullSync) {
      log.debug("Falling back to HTTP recursive directory listing for full repo sync: {}", remoteUrl);
        List<String> httpAssets = listRemoteAssetsViaHttpRecursive("", remoteUrl, authUsername, authPassword);
        if (!httpAssets.isEmpty()) {
          return httpAssets;
        }
      }
      else {
      log.debug("Falling back to HTTP directory listing for remote: {}", remoteUrl);
        List<String> httpAssets = listRemoteAssetsViaHttp(directoryPath, remoteUrl, authUsername, authPassword);
        if (!httpAssets.isEmpty()) {
          return httpAssets;
        }
      }

      // Strategy 4: Last resort - list locally cached assets
      // This only finds already-cached assets, but better than returning empty.
      log.debug("All remote listing strategies failed, falling back to locally cached assets for repo {}", repo.getName());
      return listCachedAssetsInternal(repo, directoryPath);

    }
    catch (Exception e) {
      log.error("Failed to list remote assets for {}: {}", directoryPath, e.getMessage(), e);
      return listCachedAssetsInternal(repo, directoryPath);
    }
  }

  /**
   * List remote assets with their MD5 checksums.
   * Returns a Map of (normalized path → MD5 checksum).
   * This extracts MD5 from the same Search API response that lists assets,
   * avoiding the need for a separate getRemoteAssetMd5() call per asset.
   */
  private Map<String, String> listRemoteAssetsWithMd5(final Repository repo, final String directoryPath) {
    Map<String, String> assetMd5Map = new java.util.LinkedHashMap<>();
    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());

      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) return assetMd5Map;

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) return assetMd5Map;

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return assetMd5Map;

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      String[] repoAuth = extractAuthFromRepo(repo);
      String authUsername = (repoAuth != null) ? repoAuth[0] : null;
      String authPassword = (repoAuth != null) ? repoAuth[1] : null;
      String effectiveAuth = (authUsername != null && authPassword != null)
          ? authUsername + ":" + authPassword : null;

      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);

      // Strategy 1: Local Nexus repo
      if (remoteRepoName != null) {
        try {
          Repository remoteRepo = repositoryManager.get(remoteRepoName);
          if (remoteRepo != null) {
            log.debug("listRemoteAssetsWithMd5: Strategy 1 - local Nexus repo '{}' found, querying with MD5", remoteRepoName);
            assetMd5Map.putAll(listAssetsWithMd5ViaApi(repo.getName(), remoteRepoName,
                isFullSync ? "" : directoryPath, authUsername, authPassword));
            log.debug("listRemoteAssetsWithMd5: Strategy 1 returned {} assets", assetMd5Map.size());
            if (!assetMd5Map.isEmpty()) {
              return assetMd5Map;
            }
          }
          else {
            log.debug("listRemoteAssetsWithMd5: Strategy 1 - remote repo '{}' not found locally, trying next strategy", remoteRepoName);
          }
        }
        catch (Exception e) {
          log.warn("listRemoteAssetsWithMd5: Strategy 1 failed for repo '{}': {}", remoteRepoName, e.getMessage());
        }
      }

      // Strategy 2: Remote Nexus Search API
      if (remoteRepoName != null) {
        String remoteBaseUrl = remoteUrl.substring(0, remoteUrl.indexOf("/repository/"));
        try {
          String searchUrlBase = remoteBaseUrl + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
          if (!isFullSync) {
            String normalizedDir = directoryPath;
            if (normalizedDir.startsWith("/")) normalizedDir = normalizedDir.substring(1);
            if (normalizedDir.endsWith("/")) normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
            searchUrlBase += "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
          }
          log.debug("listRemoteAssetsWithMd5: Strategy 2 - remote Nexus Search API: {}", searchUrlBase);
          Map<String, String> remoteMd5Map = listAssetsWithMd5ViaApiUrl(searchUrlBase, effectiveAuth);
          log.debug("listRemoteAssetsWithMd5: Strategy 2 returned {} assets", remoteMd5Map.size());
          if (!remoteMd5Map.isEmpty()) {
            assetMd5Map.putAll(remoteMd5Map);
            return assetMd5Map;
          }
        }
        catch (Exception e) {
          log.warn("listRemoteAssetsWithMd5: Strategy 2 failed: {}", e.getMessage());
        }
      }

      // Strategy 3: If Search API didn't return MD5s, fall back to getRemoteAssetMd5 per asset
      // This is the slow path but ensures we get MD5s for HTTP-listed assets
      log.debug("listRemoteAssetsWithMd5: Strategy 3 - falling back to per-asset MD5 query");
      List<String> assetPaths = listRemoteAssets(repo, directoryPath);
      for (String assetPath : assetPaths) {
        String normalizedPath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        if (!assetMd5Map.containsKey(normalizedPath)) {
          String md5 = getRemoteAssetMd5(repo, normalizedPath);
          assetMd5Map.put(normalizedPath, md5);
        }
      }
    }
    catch (Exception e) {
      log.error("Failed to list remote assets with MD5: {}", e.getMessage());
    }
    return assetMd5Map;
  }

  /**
   * List assets with MD5 from a local Nexus repo via Search API.
   */
  private Map<String, String> listAssetsWithMd5ViaApi(final String localRepoName, final String remoteRepoName,
      final String directoryPath, final String authUsername, final String authPassword) throws Exception {
    String effectiveAuth = (authUsername != null && authPassword != null)
        ? authUsername + ":" + authPassword : null;

    boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
    if (isFullSync) {
      String allApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
      return listAssetsWithMd5ViaApiUrl(allApiUrl, effectiveAuth);
    }

    String normalizedDir = directoryPath;
    if (normalizedDir.endsWith("/")) {
      normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
    }

    // Try with group filter first
    String groupApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName
        + "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
    Map<String, String> assetsWithGroup = listAssetsWithMd5ViaApiUrl(groupApiUrl, effectiveAuth);

    if (!assetsWithGroup.isEmpty()) {
      // Filter by path prefix
      Map<String, String> filtered = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, String> entry : assetsWithGroup.entrySet()) {
        String path = entry.getKey();
        if (path.equals(normalizedDir) || path.startsWith(normalizedDir + "/")) {
          filtered.put(path, entry.getValue());
        }
      }
      return filtered;
    }

    // Fall back to listing all assets and filtering
    String allApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
    Map<String, String> allAssets = listAssetsWithMd5ViaApiUrl(allApiUrl, effectiveAuth);
    Map<String, String> filtered = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> entry : allAssets.entrySet()) {
      String path = entry.getKey();
      if (path.equals(normalizedDir) || path.startsWith(normalizedDir + "/")) {
        filtered.put(path, entry.getValue());
      }
    }
    return filtered;
  }

  /**
   * List assets from a remote Nexus instance via its Search API.
   * This works for external Nexus instances that expose the REST API.
   * Can discover ALL remote assets, including those not yet cached locally.
   *
   * The remoteUrl is the proxy repo's remote URL, e.g. https://remote-nexus:8081/repository/my-repo/
   * We extract the base URL and repository name, then call the Search API.
   */
  private List<String> listAssetsViaRemoteNexusApi(final String remoteUrl, final String localRepoName,
      final String directoryPath, final String authUsername, final String authPassword) {
    List<String> results = new ArrayList<>();
    try {
      // Extract base URL and repo name from remote URL
      // Pattern: <base-url>/repository/<repo-name>/
      // e.g. https://host:8081/repository/my-repo/ → base=https://host:8081, repo=my-repo
      // e.g. https://host/nexus/repository/my-repo/ → base=https://host/nexus, repo=my-repo
      String remoteRepoName = null;
      String remoteBaseUrl = null;

      int repoIdx = remoteUrl.indexOf("/repository/");
      if (repoIdx > 0) {
        remoteBaseUrl = remoteUrl.substring(0, repoIdx);
        String repoPart = remoteUrl.substring(repoIdx + "/repository/".length());
        if (repoPart.endsWith("/")) {
          repoPart = repoPart.substring(0, repoPart.length() - 1);
        }
        int slashIdx = repoPart.indexOf('/');
        remoteRepoName = (slashIdx > 0) ? repoPart.substring(0, slashIdx) : repoPart;
      }

      if (remoteRepoName == null || remoteBaseUrl == null) {
        log.debug("Cannot extract Nexus base URL and repo name from remote URL: {}", remoteUrl);
        return results;
      }

      log.debug("Remote Nexus API: baseUrl={}, repoName={}, auth={}", remoteBaseUrl, remoteRepoName,
          authUsername != null ? authUsername : "none");

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String effectiveAuth = (authUsername != null && authPassword != null)
          ? authUsername + ":" + authPassword : null;

      // Try Search API with pagination
      String searchUrlBase = remoteBaseUrl + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
      String continuationToken = null;

      do {
        String searchUrl = searchUrlBase;
        if (!isFullSync) {
          String normalizedDir = directoryPath;
          if (normalizedDir.startsWith("/")) {
            normalizedDir = normalizedDir.substring(1);
          }
          if (normalizedDir.endsWith("/")) {
            normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
          }
          searchUrl += "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
        }
        if (continuationToken != null) {
          searchUrl += "&continuationToken=" + continuationToken;
        }

        log.debug("Calling remote Nexus Search API: {}", searchUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(searchUrl).openConnection();
        SslHelper.applyTrustAllSsl(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        log.debug("Remote Nexus Search API response: HTTP {} for {}", code, searchUrl);

        if (code == 404) {
          log.debug("Remote Nexus Search API not available at {}, trying Components API", remoteBaseUrl);
          conn.disconnect();
          // Fall back to Components API
          return listAssetsViaRemoteComponentsApi(remoteBaseUrl, remoteRepoName,
              directoryPath, effectiveAuth);
        }
        if (code == 403 || code == 401) {
          log.debug("Remote Nexus Search API returned {} - authentication required for {}", code, remoteBaseUrl);
          conn.disconnect();
          return results;
        }
        if (code != 200) {
          log.debug("Remote Nexus Search API returned HTTP {} for {}", code, searchUrl);
          conn.disconnect();
          return results;
        }

        String json = readResponse(conn);
        conn.disconnect();

        if (log.isDebugEnabled()) {
          log.debug("Search API response (first 500 chars): {}", json.substring(0, Math.min(json.length(), 500)));
        }

        // Parse assets from response
        List<String> pageResults = parseSearchApiAssets(json, directoryPath);
        results.addAll(pageResults);

        // Extract continuation token for next page
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.debug("Remote Nexus Search API found {} assets from {}/{}", results.size(), remoteBaseUrl, remoteRepoName);

      // If Search API found nothing, try Components API as fallback
      if (results.isEmpty()) {
        log.debug("Search API returned 0 results, trying Components API as fallback");
        List<String> compResults = listAssetsViaRemoteComponentsApi(remoteBaseUrl, remoteRepoName,
            directoryPath, effectiveAuth);
        if (!compResults.isEmpty()) {
          return compResults;
        }
      }
    }
    catch (Exception e) {
      log.debug("Remote Nexus Search API failed for {}: {}", remoteUrl, e.getMessage());
    }
    return results;
  }

  /**
   * List assets from a remote Nexus instance via its Components API.
   * Fallback when Search API returns 0 results or is not available.
   */
  private List<String> listAssetsViaRemoteComponentsApi(final String remoteBaseUrl, final String remoteRepoName,
      final String directoryPath, final String effectiveAuth) {
    List<String> results = new ArrayList<>();
    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String compUrlBase = remoteBaseUrl + "/service/rest/v1/components?repository=" + remoteRepoName;
      String continuationToken = null;

      do {
        String compUrl = compUrlBase;
        if (continuationToken != null) {
          compUrl += "&continuationToken=" + continuationToken;
        }

        log.debug("Calling remote Nexus Components API: {}", compUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(compUrl).openConnection();
        SslHelper.applyTrustAllSsl(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        log.debug("Remote Nexus Components API response: HTTP {}", code);

        if (code != 200) {
          conn.disconnect();
          return results;
        }

        String json = readResponse(conn);
        conn.disconnect();

        // Parse components and extract asset paths
        List<String> pageResults = parseComponentsApiAssets(json, directoryPath);
        results.addAll(pageResults);

        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.debug("Remote Nexus Components API found {} assets from {}/{}", results.size(), remoteBaseUrl, remoteRepoName);
    }
    catch (Exception e) {
      log.debug("Remote Nexus Components API failed: {}", e.getMessage());
    }
    return results;
  }

  /**
   * Parse assets from Nexus Components API response.
   * Each component has an "assets" array with asset objects containing "path".
   */
  private List<String> parseComponentsApiAssets(final String json, final String directoryPath) {
    List<String> results = new ArrayList<>();
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return results;

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String normalizedPrefix = directoryPath;
      if (normalizedPrefix != null && normalizedPrefix.startsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(1);
      }
      if (normalizedPrefix != null && normalizedPrefix.endsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
      }

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String component = itemsSection.substring(objStart, objEnd + 1);

        // Extract assets array from component
        String assetsSection = extractJsonArray(component, "assets");
        if (assetsSection != null) {
          int aPos = 0;
          while (aPos < assetsSection.length()) {
            int aObjStart = assetsSection.indexOf('{', aPos);
            if (aObjStart < 0) break;
            int aObjEnd = findMatchingBrace(assetsSection, aObjStart);
            if (aObjEnd < 0) break;

            String asset = assetsSection.substring(aObjStart, aObjEnd + 1);
            String path = extractJsonValue(asset, "path");
            if (path != null && !path.isEmpty()) {
              if (path.startsWith("/")) path = path.substring(1);
              if (!path.endsWith("/")) {
                if (isFullSync) {
                  results.add(path);
                }
                else if (path.startsWith(normalizedPrefix + "/")) {
                  results.add(path);
                }
              }
            }

            aPos = aObjEnd + 1;
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to parse Components API assets: {}", e.getMessage());
    }
    return results;
  }

  /**
   * Parse assets from Nexus Search API response.
   * Extracts asset paths from the "items" array.
   */
  private List<String> parseSearchApiAssets(final String json, final String directoryPath) {
    List<String> results = new ArrayList<>();
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return results;

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String normalizedPrefix = directoryPath;
      if (normalizedPrefix != null && normalizedPrefix.startsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(1);
      }
      if (normalizedPrefix != null && normalizedPrefix.endsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
      }

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String item = itemsSection.substring(objStart, objEnd + 1);
        String path = extractJsonValue(item, "path");
        if (path != null && !path.isEmpty()) {
          // Normalize: strip leading slash
          if (path.startsWith("/")) path = path.substring(1);

          // Skip directory entries
          if (!path.endsWith("/")) {
            if (isFullSync) {
              results.add(path);
            }
            else if (path.startsWith(normalizedPrefix + "/")) {
              results.add(path);
            }
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to parse Search API assets: {}", e.getMessage());
    }
    return results;
  }

  /**
   * List locally cached assets in a repository using Nexus internal StorageTx API.
   * This is the most reliable approach for proxy repositories, as it bypasses
   * HTTP authentication and directory listing issues entirely.
   * Works for all repository types including proxy repositories.
   */
  private List<String> listCachedAssetsInternal(final Repository repo, final String directoryPath) {
    List<String> results = new ArrayList<>();
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) {
        log.debug("Bucket not found for repository: {}", repo.getName());
        return results;
      }

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String normalizedPrefix = directoryPath;
      if (normalizedPrefix != null && normalizedPrefix.startsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(1);
      }
      if (normalizedPrefix != null && normalizedPrefix.endsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
      }

      Iterable<Asset> assets = tx.browseAssets(bucket);
      for (Asset asset : assets) {
        String name = asset.name();
        if (name == null) continue;

        // Normalize: strip leading slash
        String normalizedName = name.startsWith("/") ? name.substring(1) : name;

        // Skip directory entries
        if (normalizedName.endsWith("/")) continue;

        if (isFullSync) {
          results.add(normalizedName);
        }
        else {
          // Include assets under the path prefix
          if (normalizedName.startsWith(normalizedPrefix + "/")) {
            results.add(normalizedName);
          }
        }
      }

      log.debug("Internal API found {} cached assets in repo {} under prefix '{}'",
          results.size(), repo.getName(), isFullSync ? "/" : normalizedPrefix);
    }
    catch (Exception e) {
      log.debug("Internal API search failed for repo {}/{}: {}", repo.getName(), directoryPath, e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return results;
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
   * Uses proxy-configured credentials if provided.
   */
  private List<String> listAssetsViaApi(final String repoName, final String directoryPath,
      final String authUsername, final String authPassword) throws Exception {
    String effectiveAuth = (authUsername != null && authPassword != null)
        ? authUsername + ":" + authPassword : null;

    // Full repository sync — list all assets without any path filter
    boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
    if (isFullSync) {
      String allApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + repoName;
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
    String groupApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + repoName
        + "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
    List<String> assetsWithGroup = listAssetsViaApiUrl(groupApiUrl, effectiveAuth);

    // Check if group filter returned results — if yes, return them directly
    // but still filter by path prefix to ensure accuracy
    if (!assetsWithGroup.isEmpty()) {
      List<String> filtered = filterByPathPrefix(assetsWithGroup, pathPrefix);
      log.debug("Search with group filter returned {} assets, {} matched path prefix '{}'",
          assetsWithGroup.size(), filtered.size(), pathPrefix);
      return filtered;
    }

    // Step 2: Group filter returned 0 results — fall back to listing all assets
    // and filtering by path prefix. This handles raw/hosted repos where the Search API's
    // group parameter doesn't correspond to the file system directory path.
    log.debug("Group filter returned 0 results for '{}', falling back to path-prefix filter", normalizedDir);
    String allApiUrl = getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + repoName;
    List<String> allAssets = listAssetsViaApiUrl(allApiUrl, effectiveAuth);
    List<String> filtered = filterByPathPrefix(allAssets, pathPrefix);

    log.debug("Total assets in repo {}: {}, matched path prefix '{}': {}",
        repoName, allAssets.size(), pathPrefix, filtered.size());

    return filtered;
  }

  /**
   * List assets from a Search API URL, handling pagination.
   * Returns all asset paths found across all pages.
   */
  private List<String> listAssetsViaApiUrl(final String apiUrl, final String effectiveAuth) throws Exception {
    // Use LinkedHashSet to preserve order while preventing duplicates
    java.util.LinkedHashSet<String> assetNamesSet = new java.util.LinkedHashSet<>();
    String continuationToken = null;

    do {
      String url = apiUrl;
      if (continuationToken != null) {
        url += "&continuationToken=" + continuationToken;
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));

      if (conn.getResponseCode() != 200) {
        log.warn("Search API returned HTTP {} for {}", conn.getResponseCode(), url);
        conn.disconnect();
        return new ArrayList<>(assetNamesSet);
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
            // Normalize: strip leading slash for consistent path matching
            if (path.startsWith("/")) {
              path = path.substring(1);
            }
            assetNamesSet.add(path);
          }

          pos = objEnd + 1;
        }
      }

      continuationToken = extractJsonValue(json, "continuationToken");

    } while (continuationToken != null && !continuationToken.isEmpty());

    return new ArrayList<>(assetNamesSet);
  }

  /**
   * List assets from a Search API URL, extracting both path and MD5 checksum.
   * Returns a Map of (normalized path → MD5 checksum).
   * This avoids the need for a separate getRemoteAssetMd5() call per asset.
   */
  private Map<String, String> listAssetsWithMd5ViaApiUrl(final String apiUrl, final String effectiveAuth) throws Exception {
    Map<String, String> assetMap = new java.util.LinkedHashMap<>();
    String continuationToken = null;
    int md5Found = 0;
    int md5Missing = 0;
    boolean loggedFirstMissingItem = false;

    do {
      String url = apiUrl;
      if (continuationToken != null) {
        url += "&continuationToken=" + continuationToken;
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));

      if (conn.getResponseCode() != 200) {
        log.warn("Search API returned HTTP {} for MD5 query: {}", conn.getResponseCode(), url);
        conn.disconnect();
        return assetMap;
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
            // Normalize: strip leading slash for consistent path matching
            if (path.startsWith("/")) {
              path = path.substring(1);
            }
            // Extract MD5 from checksums in the same item
            String md5 = extractChecksumValue(item, "md5");
            assetMap.put(path, md5);
            if (md5 != null) {
              md5Found++;
            }
            else {
              md5Missing++;
              // Log the first item where MD5 extraction failed for diagnosis
              if (!loggedFirstMissingItem) {
                loggedFirstMissingItem = true;
                String snippet = item.length() > 500 ? item.substring(0, 500) + "..." : item;
                log.debug("MD5 extraction failed for asset '{}', raw item JSON (first 500 chars): {}", path, snippet);
              }
            }
          }

          pos = objEnd + 1;
        }
      }

      continuationToken = extractJsonValue(json, "continuationToken");

    } while (continuationToken != null && !continuationToken.isEmpty());

    log.debug("Search API MD5 extraction result: {} assets with MD5, {} assets without MD5 (total: {})",
        md5Found, md5Missing, assetMap.size());

    return assetMap;
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
      SslHelper.applyTrustAllSsl(conn);
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

      log.debug("Fetching remote directory listing from: {} (auth: {})", dirUrl, authUsername != null ? authUsername : "no");

      HttpURLConnection conn = (HttpURLConnection) new URL(dirUrl).openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");

      if (authUsername != null && authPassword != null) {
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(authUsername + ":" + authPassword));
      }

      int responseCode = conn.getResponseCode();
      log.debug("Remote directory listing response: HTTP {} for {}", responseCode, dirUrl);

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

      log.debug("Found {} remote assets via HTTP directory listing", remoteAssets.size());

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
        log.debug("Asset {} exists in cache, deleting to force fresh download", assetPath);
        tx.deleteAsset(asset);
        tx.commit();
      }
      else {
        log.debug("Asset {} not in cache, will fetch fresh from remote", assetPath);
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

  private String[] extractAuthFromRepo(final Repository repo) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) return null;
      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("httpclient")) return null;
      @SuppressWarnings("unchecked")
      Map<String, Object> httpClientAttrs = attributes.get("httpclient");
      @SuppressWarnings("unchecked")
      Map<String, Object> authAttrs = (Map<String, Object>) httpClientAttrs.get("authentication");
      if (authAttrs == null) return null;
      String username = (String) authAttrs.get("username");
      String password = (String) authAttrs.get("password");
      if (username != null && password != null) return new String[]{username, password};
    }
    catch (Exception e) {
      log.debug("Failed to extract auth from repo {}: {}", repo.getName(), e.getMessage());
    }
    return null;
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

  private String extractChecksumValue(final String json, final String algo) {
    // Find "checksums" key
    String checksumsPattern = "\"checksums\"";
    int checksumsIdx = json.indexOf(checksumsPattern);
    if (checksumsIdx >= 0) {
      // Find the opening brace of the checksums object
      int objStart = json.indexOf('{', checksumsIdx + checksumsPattern.length());
      if (objStart >= 0) {
        // Find the matching closing brace
        int objEnd = findMatchingBrace(json, objStart);
        if (objEnd >= 0) {
          // Extract the checksums object and find the algorithm value
          String checksumsObj = json.substring(objStart, objEnd + 1);
          String value = extractJsonValue(checksumsObj, algo);
          if (value != null) return value;
        }
      }
    }

    // Fallback: try to find the algorithm directly in the JSON
    // Some Nexus versions or formats may structure checksums differently
    // e.g. "checksum": {"md5": "..."} or "md5": "..." at top level
    String algoPattern = "\"" + algo + "\"";
    int algoIdx = json.indexOf(algoPattern);
    if (algoIdx >= 0) {
      int colonIdx = json.indexOf(':', algoIdx + algoPattern.length());
      if (colonIdx >= 0) {
        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart >= 0) {
          int valueEnd = valueStart + 1;
          while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == '"' && json.charAt(valueEnd - 1) != '\\') break;
            valueEnd++;
          }
          if (valueEnd < json.length()) {
            return json.substring(valueStart + 1, valueEnd);
          }
        }
      }
    }

    return null;
  }

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

  // ========================================================================
  // Incremental sync helpers: MD5 comparison
  // ========================================================================

  /**
   * Build a map of asset path -> MD5 for all locally cached assets under a directory.
   */
  private Map<String, String> buildLocalAssetMd5Map(final Repository repo, final String directoryPath) {
    Map<String, String> md5Map = new HashMap<>();
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) {
        log.debug("Bucket not found for repository: {}", repo.getName());
        return md5Map;
      }

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String normalizedPrefix = directoryPath;
      if (normalizedPrefix != null && normalizedPrefix.startsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(1);
      }
      if (normalizedPrefix != null && normalizedPrefix.endsWith("/")) {
        normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
      }

      Iterable<Asset> assets = tx.browseAssets(bucket);
      for (Asset asset : assets) {
        String name = asset.name();
        if (name == null) continue;

        String normalizedName = name.startsWith("/") ? name.substring(1) : name;
        if (normalizedName.endsWith("/")) continue;

        if (isFullSync || normalizedName.startsWith(normalizedPrefix + "/")) {
          // Get MD5 checksum from asset attributes
          String md5 = getAssetMd5(asset);
          if (md5 != null) {
            md5Map.put(normalizedName, md5);
          }
        }
      }

      log.debug("Built local MD5 map with {} entries for {}:{}",
          md5Map.size(), repo.getName(), isFullSync ? "/" : normalizedPrefix);
    }
    catch (Exception e) {
      log.warn("Failed to build local MD5 map for {}:{}: {}", repo.getName(), directoryPath, e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return md5Map;
  }

  /**
   * Get MD5 checksum from an asset's attributes.
   */
  private String getAssetMd5(final Asset asset) {
    try {
      // Method 1: Use Asset.getChecksum(HashAlgorithm.MD5) — Nexus 3.7+
      try {
        com.google.common.hash.HashCode md5Hash = asset.getChecksum(org.sonatype.nexus.common.hash.HashAlgorithm.MD5);
        if (md5Hash != null) {
          String md5 = md5Hash.toString();
          if (!md5.isEmpty()) return md5;
        }
      }
      catch (Exception ignored) { }

      // Method 2: Try checksum attribute map
      Map<String, Object> attributes = asset.attributes() != null ? asset.attributes().backing() : null;
      if (attributes != null && attributes.containsKey("checksum")) {
        @SuppressWarnings("unchecked")
        Map<String, String> checksums = (Map<String, String>) attributes.get("checksum");
        if (checksums != null) {
          String md5 = checksums.get("md5");
          if (md5 != null) return md5;
        }
      }
    }
    catch (Exception e) {
      log.debug("Failed to get MD5 for asset {}: {}", asset.name(), e.getMessage());
    }
    return null;
  }

  /**
   * Get MD5 checksum of a remote asset by querying the Nexus Search API or Components API.
   * For non-Nexus remotes, falls back to HTTP HEAD request to get Content-MD5 or ETag.
   */
  private String getRemoteAssetMd5(final Repository repo, final String assetPath) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        log.debug("getRemoteAssetMd5: config is null for {}", repo.getName());
        return null;
      }

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) {
        log.debug("getRemoteAssetMd5: no proxy attributes for {}", repo.getName());
        return null;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        log.debug("getRemoteAssetMd5: remoteUrl is empty for {}", repo.getName());
        return null;
      }

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      String[] repoAuth = extractAuthFromRepo(repo);
      String normalizedPath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;

      // Strategy A: Try listRemoteAssetsWithMd5 (same method used by directory sync, proven to work)
      // Extract the directory path from the asset path
      String dirPath = "";
      int lastSlash = normalizedPath.lastIndexOf('/');
      if (lastSlash > 0) {
        dirPath = normalizedPath.substring(0, lastSlash);
      }
      Map<String, String> md5Map = listRemoteAssetsWithMd5(repo, dirPath);
      if (md5Map != null && md5Map.containsKey(normalizedPath)) {
        String md5 = md5Map.get(normalizedPath);
        if (md5 != null) {
          log.debug("getRemoteAssetMd5: got MD5 {} for {}/{} via listRemoteAssetsWithMd5", md5, repo.getName(), assetPath);
          return md5;
        }
      }

      // Strategy B: Check if remote URL points to a Nexus instance, use Search API
      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        String nexusBaseUrl = remoteUrl.substring(0, remoteUrl.indexOf("/repository/"));
        String format = repo.getFormat() != null ? repo.getFormat().getValue() : "";
        String md5 = getRemoteMd5ViaNexusApi(nexusBaseUrl, remoteRepoName, repoAuth, assetPath, "/" + normalizedPath, format);
        if (md5 != null) {
          log.debug("getRemoteAssetMd5: got MD5 {} for {}/{} via Nexus API", md5, repo.getName(), assetPath);
          return md5;
        }
        log.debug("getRemoteAssetMd5: Nexus API returned null MD5 for {}/{}", repo.getName(), assetPath);
      }

      // Fallback for non-Nexus remotes or when Search API fails:
      // Try HTTP HEAD request to get Content-MD5 header or ETag
      String headMd5 = getRemoteMd5ViaHttpHead(remoteUrl, assetPath, repoAuth);
      if (headMd5 != null) {
        log.debug("getRemoteAssetMd5: got MD5 {} for {}/{} via HTTP HEAD", headMd5, repo.getName(), assetPath);
      }
      else {
        log.debug("getRemoteAssetMd5: all methods failed for {}/{}", repo.getName(), assetPath);
      }
      return headMd5;
    }
    catch (Exception e) {
      log.warn("Failed to get remote MD5 for {}/{}: {}", repo.getName(), assetPath, e.getMessage());
    }
    return null;
  }

  /**
   * Get remote MD5 via Nexus Search API with multiple query strategies.
   * Tries various combinations of group and name parameters to handle different
   * repository formats (maven2, raw, npm, etc.) and path structures.
   * @param format the repository format (e.g. "maven2", "raw", "npm") for format-aware group extraction
   */
  private String getRemoteMd5ViaNexusApi(final String nexusBaseUrl, final String remoteRepoName,
      final String[] repoAuth, final String assetPath, final String normalizedPath, final String format) {
    try {
      String group = extractGroupFromPath(assetPath, format);
      String componentName = extractComponentNameFromPath(assetPath);
      String fileName = extractFileName(assetPath);

      // Strategy 1: group + component name (parent directory name)
      // Works for Maven2 (artifactId) and some raw repos
      if (group != null && componentName != null) {
        String md5 = searchAndExtractMd5(nexusBaseUrl, remoteRepoName, repoAuth, group, componentName, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group+componentName query (group={}, name={})", assetPath, group, componentName);
          return md5;
        }
      }

      // Strategy 2: group + file name
      // Works for raw format where component name equals file name
      if (group != null && fileName != null && !fileName.equals(componentName)) {
        String md5 = searchAndExtractMd5(nexusBaseUrl, remoteRepoName, repoAuth, group, fileName, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group+fileName query (group={}, name={})", assetPath, group, fileName);
          return md5;
        }
      }

      // Strategy 3: group only (no name filter)
      if (group != null) {
        String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
            + "?repository=" + remoteRepoName
            + "&group=" + java.net.URLEncoder.encode(group, "UTF-8");
        String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group-only query (group={})", assetPath, group);
          return md5;
        }
      }

      // Strategy 4: name only (file name, no group filter)
      // Works when the component is in an unexpected group or root group
      if (fileName != null) {
        String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
            + "?repository=" + remoteRepoName
            + "&name=" + java.net.URLEncoder.encode(fileName, "UTF-8");
        String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via name-only query (name={})", assetPath, fileName);
          return md5;
        }
      }

      // Strategy 5: component name only (parent directory name, no group filter)
      if (componentName != null && !componentName.equals(fileName)) {
        String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
            + "?repository=" + remoteRepoName
            + "&name=" + java.net.URLEncoder.encode(componentName, "UTF-8");
        String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via componentName-only query (name={})", assetPath, componentName);
          return md5;
        }
      }

      // Strategy 6: For Maven2, try with path-based group (slash-separated) as additional fallback
      if ("maven2".equals(format)) {
        String pathGroup = extractGroupFromPath(assetPath, "raw");
        if (pathGroup != null && !pathGroup.equals(group)) {
          String md5 = searchAndExtractMd5(nexusBaseUrl, remoteRepoName, repoAuth, pathGroup, componentName, normalizedPath);
          if (md5 != null) {
            log.debug("Got remote MD5 for {} via path-based group query (group={})", assetPath, pathGroup);
            return md5;
          }
        }
      }

      // Strategy 7: Full repository query (last resort, may be slow for large repos)
      log.debug("Falling back to full repository query for remote MD5 of {}", assetPath);
      String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
          + "?repository=" + remoteRepoName;
      String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
      if (md5 != null) {
        log.debug("Got remote MD5 for {} via full repository query", assetPath);
      }
      return md5;
    }
    catch (Exception e) {
      log.debug("Failed to get remote MD5 via Nexus API for {}: {}", assetPath, e.getMessage());
    }
    return null;
  }

  /**
   * Search Nexus API with group and name parameters, then extract MD5 by matching path.
   */
  private String searchAndExtractMd5(final String nexusBaseUrl, final String remoteRepoName,
      final String[] repoAuth, final String group, final String name, final String normalizedPath) {
    try {
      String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
          + "?repository=" + remoteRepoName
          + "&group=" + java.net.URLEncoder.encode(group, "UTF-8")
          + "&name=" + java.net.URLEncoder.encode(name, "UTF-8");
      return queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
    }
    catch (Exception e) {
      log.debug("Search API query failed for group={}, name={}: {}", group, name, e.getMessage());
    }
    return null;
  }

  /**
   * Get remote MD5 via HTTP HEAD request.
   * Checks Content-MD5 header first, then falls back to ETag comparison.
   * For non-Nexus remotes, this is the only way to get remote checksum info.
   */
  private String getRemoteMd5ViaHttpHead(final String remoteUrl, final String assetPath,
      final String[] repoAuth) {
    HttpURLConnection conn = null;
    try {
      String fileUrl = remoteUrl + assetPath;
      java.net.URL url = new java.net.URL(fileUrl);
      conn = (HttpURLConnection) url.openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("HEAD");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(10000);

      if (repoAuth != null) {
        String auth = encodeAuth(repoAuth[0] + ":" + repoAuth[1]);
        conn.setRequestProperty("Authorization", "Basic " + auth);
      }

      int responseCode = conn.getResponseCode();
      if (responseCode != 200) {
        log.debug("HTTP HEAD returned {} for {}", responseCode, fileUrl);
        return null;
      }

      // Try Content-MD5 header (RFC 2616, base64 encoded)
      String contentMd5 = conn.getHeaderField("Content-MD5");
      if (contentMd5 != null && !contentMd5.isEmpty()) {
        try {
          byte[] md5Bytes = java.util.Base64.getDecoder().decode(contentMd5);
          return bytesToHex(md5Bytes);
        }
        catch (Exception e) {
          log.debug("Failed to decode Content-MD5 header '{}': {}", contentMd5, e.getMessage());
        }
      }

      // Try ETag header as a fallback for change detection
      String etag = conn.getHeaderField("ETag");
      if (etag != null && !etag.isEmpty()) {
        String cleanedEtag = etag.replace("\"", "").trim();
        if (!cleanedEtag.isEmpty()) {
          log.debug("Using ETag as fallback checksum for {}: {}", assetPath, cleanedEtag);
          return "etag:" + cleanedEtag;
        }
      }
    }
    catch (Exception e) {
      log.debug("Failed to get remote MD5 via HTTP HEAD for {}: {}", assetPath, e.getMessage());
    }
    finally {
      if (conn != null) {
        try { conn.disconnect(); } catch (Exception ignored) { }
      }
    }
    return null;
  }

  /**
   * Convert byte array to lowercase hex string.
   */
  private static String bytesToHex(final byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }

  /**
   * Query remote Nexus Search API and extract MD5 for a specific asset path.
   * Handles pagination to search through all results.
   */
  private String queryRemoteMd5(final String searchUrlBase, final String[] repoAuth, final String normalizedPath) {
    HttpURLConnection conn = null;
    try {
      String continuationToken = null;
      do {
        String url = searchUrlBase;
        if (continuationToken != null) {
          url += "&continuationToken=" + continuationToken;
        }

        java.net.URL searchUrl = new java.net.URL(url);
        conn = (HttpURLConnection) searchUrl.openConnection();
        SslHelper.applyTrustAllSsl(conn);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        if (repoAuth != null) {
          String auth = encodeAuth(repoAuth[0] + ":" + repoAuth[1]);
          conn.setRequestProperty("Authorization", "Basic " + auth);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
          log.debug("Search API returned HTTP {} for MD5 query", responseCode);
          return null;
        }

        String response = readResponse(conn);
        String md5 = extractChecksumForPath(response, normalizedPath, "md5");
        if (md5 != null) {
          return md5;
        }

        // Check next page
        continuationToken = extractJsonValue(response, "continuationToken");
        conn.disconnect();
        conn = null;
      } while (continuationToken != null && !continuationToken.isEmpty());
    }
    catch (Exception e) {
      log.debug("Failed to query remote MD5: {}", e.getMessage());
    }
    finally {
      if (conn != null) {
        try { conn.disconnect(); } catch (Exception ignored) { }
      }
    }
    return null;
  }

  /**
   * Extract the group from an asset path for Nexus Search API.
   * For Maven2 format: converts path to dot-separated groupId without version.
   *   e.g. "com/example/my-app/1.0/my-app-1.0.jar" → "com.example.my-app"
   * For other formats: uses the parent directory path as-is.
   *   e.g. "some/dir/file.txt" → "some/dir"
   */
  private String extractGroupFromPath(final String assetPath, final String format) {
    if (assetPath == null || assetPath.isEmpty()) return null;
    int lastSlash = assetPath.lastIndexOf('/');
    if (lastSlash <= 0) {
      // No parent directory or only root — asset is at the root level
      // Return empty string for root group (Nexus uses "" for root-level components)
      return lastSlash == 0 ? "" : null;
    }
    String parentPath = assetPath.substring(0, lastSlash);

    if ("maven2".equals(format)) {
      // Maven2 path structure: groupId/artifactId/version/artifactId-version.ext
      // The version directory is the last segment of parentPath.
      // We need to extract groupId (everything before version/artifactId).
      int versionSlash = parentPath.lastIndexOf('/');
      if (versionSlash > 0) {
        String possibleVersion = parentPath.substring(versionSlash + 1);
        // Check if this looks like a version (starts with digit)
        if (possibleVersion.length() > 0 && Character.isDigit(possibleVersion.charAt(0))) {
          String groupPart = parentPath.substring(0, versionSlash);
          // Convert slash-separated path to dot-separated groupId
          return groupPart.replace('/', '.');
        }
      }
      // Fallback: if no version detected, use the full parent path as dot-separated
      return parentPath.replace('/', '.');
    }

    // For non-Maven formats, use the parent directory path as-is
    return parentPath;
  }

  /**
   * Extract the component name from an asset path.
   * For Maven repos, the component name is typically the directory name
   * just above the version directory (i.e., the artifactId).
   * e.g. "com/example/my-app/1.0/my-app-1.0.jar" → "my-app"
   * For non-Maven paths, use the parent directory name.
   * e.g. "some/dir/file.txt" → "dir"
   */
  private String extractComponentNameFromPath(final String assetPath) {
    if (assetPath == null || assetPath.isEmpty()) return null;
    int lastSlash = assetPath.lastIndexOf('/');
    if (lastSlash < 0) {
      // No slash — file is at root, component name is the file name itself
      return assetPath;
    }
    if (lastSlash == 0) {
      // Path starts with "/" — treat as root-level file
      return assetPath.substring(1);
    }
    String parentPath = assetPath.substring(0, lastSlash);
    int parentSlash = parentPath.lastIndexOf('/');
    return parentSlash >= 0 ? parentPath.substring(parentSlash + 1) : parentPath;
  }

  /**
   * Extract the file name from an asset path.
   * e.g. "com/example/artifact/1.0/artifact-1.0.jar" → "artifact-1.0.jar"
   * e.g. "1/test_network_daemon.sh" → "test_network_daemon.sh"
   */
  private String extractFileName(final String assetPath) {
    if (assetPath == null || assetPath.isEmpty()) return null;
    int lastSlash = assetPath.lastIndexOf('/');
    return lastSlash >= 0 ? assetPath.substring(lastSlash + 1) : assetPath;
  }

  /**
   * Extract checksum value from a Search API response for a specific asset path.
   * The response may contain multiple assets (e.g. .jar + .pom with same name),
   * so we match by path to find the correct one.
   */
  private String extractChecksumForPath(final String json, final String targetPath, final String algo) {
    String itemsSection = extractJsonArray(json, "items");
    if (itemsSection == null) return null;

    int pos = 0;
    while (pos < itemsSection.length()) {
      int objStart = itemsSection.indexOf("{", pos);
      if (objStart < 0) break;
      int objEnd = findMatchingBrace(itemsSection, objStart);
      if (objEnd < 0) break;

      String item = itemsSection.substring(objStart, objEnd + 1);
      String itemPath = extractJsonValue(item, "path");

      if (itemPath != null && itemPath.equals(targetPath)) {
        return extractChecksumValue(item, algo);
      }

      pos = objEnd + 1;
    }

    return null;
  }
}
