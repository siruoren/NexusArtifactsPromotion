package com.nexus.artifacts.promotion.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Query;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;

/**
 * Service for remote repository sync.
 * Supports sync of directories, files, and images from remote (proxy) repositories.
 * Features:
 * - Dedup: same source directory re-submitted keeps only latest task
 * - Old tasks are cancelled and marked as migrated to new task ID
 * - Thread pool managed execution
 * - Permission checks at both submission and execution time
 */
@Named
@Singleton
public class SyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncService.class);

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;

  private final Map<String, SyncTaskInfo> taskInfos = new ConcurrentHashMap<>();

  @Inject
  public SyncService(final RepositoryManager repositoryManager,
                      final TaskExecutorService taskExecutor,
                      final TaskCacheManager cacheManager,
                      final PermissionChecker permissionChecker)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
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

    // Check sync permission
    permissionChecker.checkSyncPermission(request.getRepositoryName(), request.getFormat());

    // Check queue capacity
    if (!taskExecutor.hasSyncQueueCapacity()) {
      throw new IllegalStateException("Sync queue is full. Please wait for existing tasks to complete.");
    }

    String username = permissionChecker.getCurrentUsername();

    // Submit to thread pool with dedup handling
    String taskId = taskExecutor.submitSyncTask(() -> {
      SyncTaskInfo taskInfo = new SyncTaskInfo();
      taskInfo.setSourceRepository(request.getRepositoryName());
      taskInfo.setPath(request.getPath());
      taskInfo.setDirectory(request.isDirectory());
      taskInfo.setFormat(request.getFormat());
      taskInfo.setUsername(username);
      taskInfo.setStartTime(System.currentTimeMillis());
      taskInfo.setStatus(TaskStatus.RUNNING);

      try {
        // Re-check permission inside the task
        if (!permissionChecker.hasSyncPermission(request.getRepositoryName(), request.getFormat())) {
          throw new SecurityException("User no longer has sync permission for repository: " + request.getRepositoryName());
        }

        Repository repo = repositoryManager.get(request.getRepositoryName());
        if (repo == null) {
          throw new IllegalArgumentException("Repository not found: " + request.getRepositoryName());
        }

        // Determine target repository (local cache of the proxy repo)
        String targetRepo = determineLocalCacheRepo(repo);
        taskInfo.setTargetRepository(targetRepo);

        // Create task cache
        cacheManager.createTaskCache("sync-" + System.currentTimeMillis());

        // Execute sync
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
    }, String.format("Sync %s from %s", request.getPath(), request.getRepositoryName()),
        request.getRepositoryName(), request.getPath());

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
    SyncTaskInfo info = taskInfos.get(taskId);
    if (info != null) {
      // Update status from executor
      TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
      if (status != null) {
        info.setStatus(status);
      }
    }
    return info;
  }

  /**
   * Get all sync tasks (for queue display).
   */
  public List<SyncTaskInfo> getAllSyncTasks() {
    List<SyncTaskInfo> tasks = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : taskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      // Update status from executor
      TaskStatus status = taskExecutor.getSyncTaskStatus(entry.getKey());
      if (status != null) {
        info.setStatus(status);
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
   * Sync all files in a directory from remote.
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDirectory(final Repository repo, final String directoryPath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      StorageFacet storageFacet = repo.facet(StorageFacet.class);
      UnitOfWork.begin(storageFacet.txSupplier());
      try {
        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(repo);

        // Use Query.builder() with where/like pattern
        Query query = Query.builder()
            .where("name").like(escapeLike(directoryPath) + "%")
            .build();

        Iterable<Asset> assets = tx.findAssets(query, Collections.singletonList(repo));

        for (Asset asset : assets) {
          SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
              asset.name(), determineType(asset.name()));

          try {
            // Trigger re-download / cache refresh from remote
            syncSingleAsset(repo, asset);
            detail.setStatus("success");
          }
          catch (Exception e) {
            log.error("Failed to sync asset {}: {}", asset.name(), e.getMessage());
            detail.setStatus("failed");
            detail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          }

          details.add(detail);
        }
      }
      finally {
        UnitOfWork.end();
      }
    }
    catch (Exception e) {
      log.error("Failed to sync directory {}: {}", directoryPath, e.getMessage(), e);
      throw new RuntimeException("Directory sync failed", e);
    }

    return details;
  }

  /**
   * Sync a single file from remote.
   */
  private List<SyncTaskInfo.SyncFileDetail> syncFile(final Repository repo, final String filePath) {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      StorageFacet storageFacet = repo.facet(StorageFacet.class);
      UnitOfWork.begin(storageFacet.txSupplier());
      try {
        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(repo);

        Asset asset = tx.findAssetWithProperty("name", filePath, bucket);
        if (asset == null) {
          throw new IllegalArgumentException("Asset not found: " + filePath);
        }

        SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(
            filePath, determineType(filePath));

        try {
          syncSingleAsset(repo, asset);
          detail.setStatus("success");
        }
        catch (Exception e) {
          detail.setStatus("failed");
          detail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        }

        details.add(detail);
      }
      finally {
        UnitOfWork.end();
      }
    }
    catch (Exception e) {
      log.error("Failed to sync file {}: {}", filePath, e.getMessage(), e);
      throw new RuntimeException("File sync failed", e);
    }

    return details;
  }

  /**
   * Sync a single asset by invalidating its cache and re-fetching from remote.
   * For proxy repositories, we mark the asset as requiring re-download
   * by clearing its blob reference.
   */
  private void syncSingleAsset(final Repository repo, final Asset asset) throws Exception {
    StorageFacet storageFacet = repo.facet(StorageFacet.class);
    UnitOfWork.begin(storageFacet.txSupplier());
    try {
      StorageTx tx = UnitOfWork.currentTx();

      // Mark asset as requiring re-download by clearing blob reference
      if (asset.blobRef() != null) {
        asset.blobRef(null);
        tx.saveAsset(asset);
      }

      tx.commit();
    }
    finally {
      UnitOfWork.end();
    }
  }

  /**
   * Determine the local cache repository name for a proxy repository.
   */
  private String determineLocalCacheRepo(final Repository repo) {
    // For proxy repos, the local cache is typically the same repo
    // In some configurations, there may be a separate cache repo
    return repo.getName() + "-cache";
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
