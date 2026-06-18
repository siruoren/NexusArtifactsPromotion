package com.nexus.artifacts.promotion.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;

/**
 * Manages persistence and recovery of active sync task queue state.
 *
 * <p>While {@link TaskStateStore} persists completed/failed task results,
 * this manager persists active (PENDING/RUNNING) sync tasks so they can be
 * recovered after Nexus restarts.
 *
 * <p>Flow:
 * <ol>
 *   <li>Periodically persist active sync tasks to disk</li>
 *   <li>On startup, recover persisted active tasks and resubmit them</li>
 *   <li>Delete recovered task files after successful resubmission</li>
 * </ol>
 */
@Named
@Singleton
public class SyncQueuePersistenceManager {

  private static final Logger log = LoggerFactory.getLogger(SyncQueuePersistenceManager.class);

  private static final String QUEUE_STATE_DIR = System.getProperty("karaf.data", "/tmp/nexus")
      + "/sync-queue-state";

  private static final long PERSIST_INTERVAL_MS = 30_000L; // 30 seconds

  /** Maximum age for a RUNNING task before it's considered a zombie (30 minutes) */
  private static final long ZOMBIE_TASK_THRESHOLD_MS = 30 * 60 * 1000L;

  /** Maximum number of active tasks to persist (prevents large JSON files) */
  private static final int MAX_PERSISTED_TASKS = 100;

  private final Path stateDir;
  private final ObjectMapper objectMapper;
  private final TaskExecutorService taskExecutor;
  private final SyncService syncService;

  private volatile boolean running = false;
  private Thread persistThread;

  @Inject
  public SyncQueuePersistenceManager(final TaskExecutorService taskExecutor,
                                      final SyncService syncService) {
    this.taskExecutor = taskExecutor;
    this.syncService = syncService;
    this.stateDir = Paths.get(QUEUE_STATE_DIR);
    this.objectMapper = new ObjectMapper();
    this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    this.objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  /**
   * On startup, recover any persisted active sync tasks.
   */
  @PostConstruct
  public void init() {
    try {
      Files.createDirectories(stateDir);
      recoverQueueState();
      startPeriodicPersist();
      log.info("SyncQueuePersistenceManager initialized at: {}", stateDir);
    }
    catch (Exception e) {
      log.error("Failed to initialize SyncQueuePersistenceManager: {}", e.getMessage(), e);
    }
  }

  /**
   * Recover persisted active sync tasks from disk and resubmit them.
   * Detects and resets zombie tasks (RUNNING for over 30 minutes).
   */
  private void recoverQueueState() {
    if (!Files.exists(stateDir)) return;

    List<Path> recoveredFiles = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(stateDir, "queue-*.json")) {
      for (Path file : stream) {
        try {
          String json = new String(Files.readAllBytes(file), "UTF-8");
          JavaType listType = objectMapper.getTypeFactory()
              .constructParametricType(List.class, SyncTaskInfo.class);
          List<SyncTaskInfo> tasks = objectMapper.readValue(json, listType);

          for (SyncTaskInfo task : tasks) {
            // Detect zombie tasks: RUNNING for over 30 minutes
            if (task.getStatus() == TaskStatus.RUNNING) {
              long runningDuration = System.currentTimeMillis() - task.getStartTime();
              if (runningDuration > ZOMBIE_TASK_THRESHOLD_MS) {
                log.warn("Detected zombie sync task {} (running for {}ms, threshold={}ms), marking as FAILED",
                    task.getTaskId(), runningDuration, ZOMBIE_TASK_THRESHOLD_MS);
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage("Task was RUNNING when Nexus crashed - automatically marked as FAILED on recovery");
                task.setEndTime(System.currentTimeMillis());
                // Don't resubmit zombie tasks, just log them
                continue;
              }
            }

            log.info("Recovering sync task: {} (source={}, path={}, status={})",
                task.getTaskId(), task.getSourceRepository(), task.getPath(), task.getStatus());

            // Reset status to PENDING for resubmission
            task.setStatus(TaskStatus.PENDING);
            task.setStartTime(System.currentTimeMillis());
            task.setEndTime(0);
            task.setErrorMessage(null);

            try {
              resubmitSyncTask(task);
              log.info("Successfully resubmitted sync task: {}", task.getTaskId());
            }
            catch (Exception e) {
              log.error("Failed to resubmit sync task {}: {}", task.getTaskId(), e.getMessage());
            }
          }

          recoveredFiles.add(file);
        }
        catch (Exception e) {
          log.error("Failed to recover task from {}: {}", file, e.getMessage());
        }
      }
    }
    catch (IOException e) {
      log.error("Failed to scan queue state directory: {}", e.getMessage());
    }

    // Delete recovered files after processing
    for (Path file : recoveredFiles) {
      try {
        Files.delete(file);
        log.debug("Deleted recovered queue state file: {}", file);
      }
      catch (IOException e) {
        log.warn("Failed to delete recovered file {}: {}", file, e.getMessage());
      }
    }

    if (!recoveredFiles.isEmpty()) {
      log.info("Recovered {} sync queue state file(s)", recoveredFiles.size());
    }
  }

  /**
   * Resubmit a recovered sync task.
   */
  private void resubmitSyncTask(final SyncTaskInfo task) {
    // The sync service will handle the actual resubmission
    // We store the task info back into the sync service's task map
    syncService.resubmitRecoveredTask(task);
  }

  /**
   * Start periodic persistence of active sync tasks.
   */
  private void startPeriodicPersist() {
    running = true;
    persistThread = new Thread(() -> {
      while (running) {
        try {
          Thread.sleep(PERSIST_INTERVAL_MS);
          persistActiveTasks();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        catch (Exception e) {
          log.warn("Error during periodic sync queue persistence: {}", e.getMessage());
        }
      }
    }, "sync-queue-persistence");
    persistThread.setDaemon(true);
    persistThread.start();
    log.info("Sync queue periodic persistence started (interval={}ms)", PERSIST_INTERVAL_MS);
  }

  /**
   * Persist all active (PENDING/RUNNING) sync tasks to disk.
   * Limits the number of persisted tasks to prevent large JSON files.
   */
  public void persistActiveTasks() {
    Map<String, TaskExecutorService.TaskHandle> syncTasks = taskExecutor.getSyncTasks();
    List<SyncTaskInfo> activeTasks = new ArrayList<>();

    for (Map.Entry<String, TaskExecutorService.TaskHandle> entry : syncTasks.entrySet()) {
      TaskExecutorService.TaskHandle handle = entry.getValue();
      if (handle.status == TaskStatus.PENDING || handle.status == TaskStatus.RUNNING) {
        SyncTaskInfo info = new SyncTaskInfo();
        info.setTaskId(handle.taskId);
        info.setSourceRepository(handle.sourceRepository);
        info.setPath(handle.sourcePath);
        info.setTargetRepository(handle.targetRepository);
        info.setStatus(handle.status);
        info.setStartTime(handle.startTime);
        activeTasks.add(info);
      }
    }

    if (activeTasks.isEmpty()) {
      // Clean up old state files if no active tasks
      cleanupStateFiles();
      return;
    }

    // Limit the number of persisted tasks to prevent large JSON files
    if (activeTasks.size() > MAX_PERSISTED_TASKS) {
      log.warn("Active sync tasks ({}) exceeds max persisted limit ({}), truncating to most recent",
          activeTasks.size(), MAX_PERSISTED_TASKS);
      activeTasks.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));
      activeTasks = activeTasks.subList(0, MAX_PERSISTED_TASKS);
    }

    try {
      String json = objectMapper.writeValueAsString(activeTasks);
      Path file = stateDir.resolve("queue-" + System.currentTimeMillis() + ".json");
      try (BufferedWriter writer = Files.newBufferedWriter(file)) {
        writer.write(json);
      }
      log.debug("Persisted {} active sync tasks to {}", activeTasks.size(), file);

      // Clean up older state files (keep only the latest)
      cleanupOldStateFiles(file);
    }
    catch (Exception e) {
      log.error("Failed to persist sync queue state: {}", e.getMessage());
    }
  }

  /**
   * Clean up all state files (called when no active tasks remain).
   */
  private void cleanupStateFiles() {
    if (!Files.exists(stateDir)) return;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(stateDir, "queue-*.json")) {
      for (Path file : stream) {
        try {
          Files.delete(file);
        }
        catch (IOException e) {
          log.debug("Failed to delete state file {}: {}", file, e.getMessage());
        }
      }
    }
    catch (IOException e) {
      log.debug("Failed to scan state directory for cleanup: {}", e.getMessage());
    }
  }

  /**
   * Clean up older state files, keeping only the latest one.
   */
  private void cleanupOldStateFiles(final Path latestFile) {
    if (!Files.exists(stateDir)) return;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(stateDir, "queue-*.json")) {
      for (Path file : stream) {
        if (!file.equals(latestFile)) {
          try {
            Files.delete(file);
          }
          catch (IOException e) {
            log.debug("Failed to delete old state file {}: {}", file, e.getMessage());
          }
        }
      }
    }
    catch (IOException e) {
      log.debug("Failed to scan state directory for cleanup: {}", e.getMessage());
    }
  }

  /**
   * Shutdown the persistence manager.
   */
  public void shutdown() {
    running = false;
    if (persistThread != null) {
      persistThread.interrupt();
    }
    // Final persist before shutdown
    persistActiveTasks();
    log.info("SyncQueuePersistenceManager shutdown complete");
  }
}
