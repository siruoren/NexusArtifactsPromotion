package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nexus.artifacts.promotion.model.TaskStatus;

/**
 * Manages cleanup of persisted sync task queue state.
 *
 * <p>Previously, this manager persisted active (PENDING/RUNNING) sync tasks
 * and recovered them after Nexus restarts. This functionality has been removed:
 * tasks are NOT resumed after a restart. On startup, any leftover state files
 * from previous runs are cleaned up, and zombie tasks are marked as FAILED.
 */
@Named
@Singleton
public class SyncQueuePersistenceManager {

  private static final Logger log = LoggerFactory.getLogger(SyncQueuePersistenceManager.class);

  private static final String QUEUE_STATE_DIR = System.getProperty("karaf.data", "/tmp/nexus")
      + "/sync-queue-state";

  private final Path stateDir;
  private final TaskExecutorService taskExecutor;

  @Inject
  public SyncQueuePersistenceManager(final TaskExecutorService taskExecutor,
                                      final SyncService syncService) {
    this.taskExecutor = taskExecutor;
    this.stateDir = Paths.get(QUEUE_STATE_DIR);
  }

  /**
   * On startup, clean up any leftover state files from previous runs.
   * Tasks are NOT resumed after restart - they are simply discarded.
   */
  @PostConstruct
  public void init() {
    try {
      Files.createDirectories(stateDir);
      cleanupOldStateFiles();
      log.info("SyncQueuePersistenceManager initialized at: {} (task recovery disabled)", stateDir);
    }
    catch (Exception e) {
      log.error("Failed to initialize SyncQueuePersistenceManager: {}", e.getMessage(), e);
    }
  }

  /**
   * Clean up all leftover state files from previous runs.
   * Any tasks that were running when Nexus stopped are simply discarded.
   */
  private void cleanupOldStateFiles() {
    if (!Files.exists(stateDir)) return;

    int cleanedCount = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(stateDir, "queue-*.json")) {
      for (Path file : stream) {
        try {
          Files.delete(file);
          cleanedCount++;
        }
        catch (IOException e) {
          log.debug("Failed to delete state file {}: {}", file, e.getMessage());
        }
      }
    }
    catch (IOException e) {
      log.debug("Failed to scan state directory for cleanup: {}", e.getMessage());
    }

    if (cleanedCount > 0) {
      log.info("Cleaned up {} leftover sync queue state file(s) from previous run", cleanedCount);
    }
  }

  /**
   * Shutdown the persistence manager.
   * No longer persists active tasks on shutdown since recovery is disabled.
   */
  public void shutdown() {
    log.info("SyncQueuePersistenceManager shutdown complete");
  }
}
