package com.nexus.artifacts.promotion.task;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.nexus.artifacts.promotion.model.DockerImageRequest;
import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.service.DockerService;
import com.nexus.artifacts.promotion.service.SyncService;

/**
 * Scheduled task for proxy repository sync.
 * Appears in Nexus Tasks UI as "Proxy Repository Scheduled Sync".
 *
 * Supports two modes:
 * - Directory sync: sync a specific path within the proxy repository
 * - Full repository sync: sync the entire proxy repository (when sync path is empty)
 *
 * Unlike the REST API sync, this task:
 * - Skips permission checks (tasks are created by administrators)
 * - Executes synchronously within the task's own thread
 * - Properly tracks task status in Nexus's task management UI
 */
@Named(ProxySyncTaskDescriptor.TYPE_ID)
public class ProxySyncTask
    extends TaskSupport
{
  private static final Logger log = LoggerFactory.getLogger(ProxySyncTask.class);

  private final SyncService syncService;
  private final RepositoryManager repositoryManager;
  private final DockerService dockerService;

  @Inject
  public ProxySyncTask(final SyncService syncService,
                       final RepositoryManager repositoryManager,
                       final DockerService dockerService)
  {
    this.syncService = syncService;
    this.repositoryManager = repositoryManager;
    this.dockerService = dockerService;
  }

  @Override
  protected Object execute() throws Exception {
    TaskConfiguration config = getConfiguration();
    String repositoryName = config.getString(ProxySyncTaskDescriptor.PROP_REPOSITORY_NAME);
    String syncPath = config.getString(ProxySyncTaskDescriptor.PROP_SYNC_PATH);

    // Validate repository name
    if (repositoryName == null || repositoryName.trim().isEmpty()) {
      throw new IllegalArgumentException("Repository name is required");
    }

    // Validate repository exists and is proxy type
    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + repositoryName);
    }
    if (!"proxy".equals(repo.getType().getValue())) {
      throw new IllegalArgumentException("Repository is not a proxy type: " + repositoryName);
    }

    String format = repo.getFormat().getValue();
    boolean isFullSync = (syncPath == null || syncPath.trim().isEmpty());

    log.info("Starting scheduled {} for repository {} (format: {})",
        isFullSync ? "full repository sync" : "directory sync",
        repositoryName, format);

    // Handle Docker repositories separately
    if ("docker".equalsIgnoreCase(format)) {
      log.info("Docker repository detected, delegating to DockerService");
      DockerImageRequest dockerRequest = new DockerImageRequest();
      dockerRequest.setSourceRepository(repositoryName);
      dockerRequest.setFormat(format);
      // tags=null means all tags (isAllTags() returns true)
      dockerRequest.setTags(null);
      // Extract image name from path if provided
      if (syncPath != null && !syncPath.trim().isEmpty()) {
        String path = syncPath;
        if (path.startsWith("v2/")) {
          path = path.substring(3);
        }
        // Remove trailing /manifests/* or /blobs/*
        int manifestsIdx = path.indexOf("/manifests/");
        int blobsIdx = path.indexOf("/blobs/");
        if (manifestsIdx > 0) {
          path = path.substring(0, manifestsIdx);
        }
        else if (blobsIdx > 0) {
          path = path.substring(0, blobsIdx);
        }
        dockerRequest.setImage(path);
        dockerRequest.setAllImages(false);
      }
      else {
        dockerRequest.setAllImages(true);
      }
      String taskId = dockerService.syncDockerImage(dockerRequest);
      String message = String.format("Docker sync task submitted for %s%s - Task ID: %s",
          repositoryName,
          isFullSync ? " (all images)" : ":" + syncPath,
          taskId);
      log.info(message);
      return message;
    }

    // Build sync request for non-Docker repositories
    SyncRequest request = new SyncRequest();
    request.setRepositoryName(repositoryName);
    request.setPath(isFullSync ? "" : syncPath);
    request.setIsDirectory(true);
    request.setFormat(format);

    // Execute sync (synchronous, no permission check for scheduled tasks)
    SyncTaskInfo result = syncService.syncScheduled(request);

    // Check result and throw if failed
    if (result.getStatus() != null && result.getStatus().name().equals("FAILED")) {
      throw new RuntimeException("Scheduled sync failed: " + result.getErrorMessage());
    }

    String message = String.format("Scheduled sync completed for %s%s - %s",
        repositoryName,
        isFullSync ? " (full repository)" : ":" + syncPath,
        result.getResult());

    log.info(message);
    return message;
  }

  @Override
  public String getMessage() {
    TaskConfiguration config = getConfiguration();
    if (config == null) {
      return "Syncing proxy repository";
    }
    String repoName = config.getString(ProxySyncTaskDescriptor.PROP_REPOSITORY_NAME);
    String syncPath = config.getString(ProxySyncTaskDescriptor.PROP_SYNC_PATH);
    boolean isFullSync = (syncPath == null || syncPath.trim().isEmpty());

    if (repoName == null || repoName.isEmpty()) {
      return "Syncing proxy repository";
    }

    return isFullSync
        ? "Full sync of proxy repository " + repoName
        : "Syncing " + syncPath + " from proxy repository " + repoName;
  }
}
