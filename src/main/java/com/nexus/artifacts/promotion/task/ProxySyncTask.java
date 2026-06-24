package com.nexus.artifacts.promotion.task;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.service.SyncService;

/**
 * Scheduled task for proxy repository sync.
 * Appears in Nexus Tasks UI as "Proxy Repository Scheduled Sync".
 *
 * Supports two modes:
 * - Directory sync: sync a specific path within the proxy repository
 * - Full repository sync: sync the entire proxy repository (when sync path is empty)
 *
 * Sync modes:
 * - Full sync (default): sync all assets regardless of local cache state
 * - Incremental sync: only sync assets whose MD5 differs from local cache
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

  @Inject
  public ProxySyncTask(final SyncService syncService,
                       final RepositoryManager repositoryManager)
  {
    this.syncService = syncService;
    this.repositoryManager = repositoryManager;
  }

  @Override
  protected Object execute() throws Exception {
    TaskConfiguration config = getConfiguration();
    String repositoryName = config.getString(ProxySyncTaskDescriptor.PROP_REPOSITORY_NAME);
    String syncPath = config.getString(ProxySyncTaskDescriptor.PROP_SYNC_PATH);
    boolean incremental = config.getBoolean(ProxySyncTaskDescriptor.PROP_INCREMENTAL, false);

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

    log.info("Starting scheduled {} {} for repository {} (format: {})",
        incremental ? "incremental" : "full",
        isFullSync ? "full repository sync" : "directory sync",
        repositoryName, format);

    // Build sync request
    SyncRequest request = new SyncRequest();
    request.setRepositoryName(repositoryName);
    request.setPath(isFullSync ? "" : syncPath);
    request.setIsDirectory(true);
    request.setFormat(format);

    // Execute sync (synchronous, no permission check for scheduled tasks)
    // Pass the task context so sync can check for cancellation
    SyncTaskInfo result;
    if (incremental) {
      result = syncService.syncScheduled(request, this, true);
    }
    else {
      result = syncService.syncScheduled(request, this);
    }

    // Build result message based on task status
    String message;
    if (result.getStatus() != null && result.getStatus().name().equals("CANCELLED")) {
      message = String.format("Scheduled %s sync cancelled for %s%s",
          incremental ? "incremental" : "full",
          repositoryName, isFullSync ? " (full repository)" : ":" + syncPath);
      log.warn(message);
      return message;
    }

    if (result.getStatus() != null && result.getStatus().name().equals("FAILED")) {
      message = String.format("Scheduled %s sync failed for %s%s - %s",
          incremental ? "incremental" : "full",
          repositoryName,
          isFullSync ? " (full repository)" : ":" + syncPath,
          result.getErrorMessage());
      log.error(message);
      // Return the message instead of throwing, so Nexus task shows warning instead of ERROR
      // and the task properly transitions to a terminal state
      return message;
    }

    message = String.format("Scheduled %s sync completed for %s%s - %s",
        incremental ? "incremental" : "full",
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
    boolean incremental = config.getBoolean(ProxySyncTaskDescriptor.PROP_INCREMENTAL, false);
    boolean isFullSync = (syncPath == null || syncPath.trim().isEmpty());

    if (repoName == null || repoName.isEmpty()) {
      return "Syncing proxy repository";
    }

    String mode = incremental ? "Incremental sync" : "Full sync";
    return isFullSync
        ? mode + " of proxy repository " + repoName
        : mode + " of " + syncPath + " from proxy repository " + repoName;
  }
}
