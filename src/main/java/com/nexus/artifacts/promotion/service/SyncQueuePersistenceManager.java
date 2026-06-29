package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.PostConstruct;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cleans up leftover sync queue state files from previous Nexus runs.
 * Task recovery after restart is not supported - tasks are simply discarded.
 */
@Named
@Singleton
public class SyncQueuePersistenceManager {

  private static final Logger log = LoggerFactory.getLogger(SyncQueuePersistenceManager.class);

  private static final String QUEUE_STATE_DIR = System.getProperty("karaf.data", "/tmp/nexus")
      + "/sync-queue-state";

  @PostConstruct
  public void init() {
    try {
      Path stateDir = Paths.get(QUEUE_STATE_DIR);
      Files.createDirectories(stateDir);
      cleanupOldStateFiles(stateDir);
      log.info("SyncQueuePersistenceManager initialized (task recovery disabled)");
    }
    catch (Exception e) {
      log.error("Failed to initialize SyncQueuePersistenceManager: {}", e.getMessage(), e);
    }
  }

  private void cleanupOldStateFiles(final Path stateDir) {
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
}
