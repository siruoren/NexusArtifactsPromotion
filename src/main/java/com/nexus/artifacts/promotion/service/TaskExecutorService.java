package com.nexus.artifacts.promotion.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nexus.artifacts.promotion.model.TaskStatus;

/**
 * Centralized thread pool manager for promotion and sync tasks.
 * Features:
 * - Configurable pool size from system settings
 * - Independent threads per task, no cross-task blocking
 * - Timeout and blocking detection per task
 * - Graceful shutdown with resource cleanup
 * - Thread naming for debugging and monitoring
 * - Memory leak prevention through proper lifecycle management
 */
@Named
@Singleton
public class TaskExecutorService {

  private static final Logger log = LoggerFactory.getLogger(TaskExecutorService.class);

  private static final int DEFAULT_PROMOTION_POOL_SIZE = 4;
  private static final int DEFAULT_SYNC_POOL_SIZE = 4;
  private static final int DEFAULT_MAX_SYNC_QUEUE_SIZE = 20;
  private static final long TASK_TIMEOUT_MINUTES = 60;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

  private volatile int promotionPoolSize = DEFAULT_PROMOTION_POOL_SIZE;
  private volatile int syncPoolSize = DEFAULT_SYNC_POOL_SIZE;
  private volatile int maxSyncQueueSize = DEFAULT_MAX_SYNC_QUEUE_SIZE;

  private ExecutorService promotionExecutor;
  private ExecutorService syncExecutor;

  private final Map<String, TaskHandle> promotionTasks = new ConcurrentHashMap<>();
  private final Map<String, TaskHandle> syncTasks = new ConcurrentHashMap<>();

  private final TaskCacheManager cacheManager;

  @Inject
  public TaskExecutorService(final TaskCacheManager cacheManager) {
    this.cacheManager = cacheManager;
    initExecutors();
  }

  private void initExecutors() {
    this.promotionExecutor = createThreadPool("promotion", promotionPoolSize);
    this.syncExecutor = createThreadPool("sync", syncPoolSize);
    log.info("TaskExecutorService initialized - promotion pool: {}, sync pool: {}, max sync queue: {}",
        promotionPoolSize, syncPoolSize, maxSyncQueueSize);
  }

  private ExecutorService createThreadPool(final String type, final int poolSize) {
    return new ThreadPoolExecutor(
        poolSize,
        poolSize,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        new TaskThreadFactory("nexus-" + type + "-task-"),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
  }

  /**
   * Submit a promotion task for execution.
   */
  public String submitPromotionTask(final Callable<PromotionTaskCallback> task,
                                     final String taskDescription)
  {
    return submitPromotionTask(task, taskDescription, generateTaskId("promo"));
  }

  /**
   * Submit a promotion task with a pre-defined taskId.
   */
  public String submitPromotionTask(final Callable<PromotionTaskCallback> task,
                                     final String taskDescription,
                                     final String taskId)
  {
    log.info("Submitting promotion task {}: {}", taskId, taskDescription);

    // Wrap task with timeout and error handling
    Callable<PromotionTaskCallback> wrappedTask = wrapTask(task, taskId, "promotion");

    Future<PromotionTaskCallback> future = promotionExecutor.submit(wrappedTask);
    promotionTasks.put(taskId, new TaskHandle(taskId, future, TaskStatus.RUNNING, System.currentTimeMillis()));

    return taskId;
  }

  /**
   * Submit a sync task for execution.
   * If the same source directory already has a pending/running task,
   * the old task is cancelled and marked as migrated.
   */
  public String submitSyncTask(final Callable<SyncTaskCallback> task,
                                final String taskDescription,
                                final String sourceRepository,
                                final String sourcePath)
  {
    final String taskId = generateTaskId("sync");
    log.info("Submitting sync task {}: {}", taskId, taskDescription);

    // Check for duplicate source directory sync - cancel old task
    cancelDuplicateSyncTask(sourceRepository, sourcePath, taskId);

    Callable<SyncTaskCallback> wrappedTask = wrapTask(task, taskId, "sync");

    Future<SyncTaskCallback> future = syncExecutor.submit(wrappedTask);
    syncTasks.put(taskId, new TaskHandle(taskId, future, TaskStatus.RUNNING, System.currentTimeMillis(),
        sourceRepository, sourcePath));

    return taskId;
  }

  /**
   * Cancel duplicate sync task for the same source directory.
   * Only keeps the latest sync task, old tasks are terminated and marked as migrated.
   */
  private void cancelDuplicateSyncTask(final String sourceRepository,
                                        final String sourcePath,
                                        final String newTaskId)
  {
    for (Map.Entry<String, TaskHandle> entry : syncTasks.entrySet()) {
      TaskHandle handle = entry.getValue();
      if (handle.sourceRepository != null
          && handle.sourceRepository.equals(sourceRepository)
          && handle.sourcePath != null
          && handle.sourcePath.equals(sourcePath)
          && (handle.status == TaskStatus.PENDING || handle.status == TaskStatus.RUNNING))
      {
        String oldTaskId = handle.taskId;
        log.info("Cancelling duplicate sync task {} for source {}:{}, migrating to {}",
            oldTaskId, sourceRepository, sourcePath, newTaskId);

        handle.future.cancel(true);
        handle.status = TaskStatus.MIGRATED;
        handle.migratedToTaskId = newTaskId;
        handle.endTime = System.currentTimeMillis();

        // Cleanup old task cache
        cacheManager.cleanupTask(oldTaskId);

        log.info("Sync task {} migrated to {}", oldTaskId, newTaskId);
      }
    }
  }

  /**
   * Wrap a task with timeout detection, error handling, and cache cleanup.
   */
  private <T> Callable<T> wrapTask(final Callable<T> task,
                                    final String taskId,
                                    final String type)
  {
    return () -> {
      Thread currentThread = Thread.currentThread();
      String originalName = currentThread.getName();
      currentThread.setName(originalName + "-" + taskId);

      try {
        log.debug("Task {} ({}) started", taskId, type);

        // Set timeout watchdog
        Thread timeoutWatchdog = new Thread(() -> {
          try {
            Thread.sleep(TimeUnit.MINUTES.toMillis(TASK_TIMEOUT_MINUTES));
            if (!currentThread.isInterrupted()) {
              log.warn("Task {} ({}) exceeded timeout of {} minutes, interrupting",
                  taskId, type, TASK_TIMEOUT_MINUTES);
              currentThread.interrupt();
            }
          }
          catch (InterruptedException e) {
            // Normal - task completed before timeout
          }
        }, "timeout-watchdog-" + taskId);
        timeoutWatchdog.setDaemon(true);
        timeoutWatchdog.start();

        T result = task.call();

        // Stop the watchdog
        timeoutWatchdog.interrupt();

        log.debug("Task {} ({}) completed successfully", taskId, type);
        return result;

      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Task {} ({}) was interrupted", taskId, type);
        throw e;
      }
      catch (Exception e) {
        log.error("Task {} ({}) failed with error: {}", taskId, type, e.getMessage(), e);
        throw e;
      }
      finally {
        currentThread.setName(originalName);

        // Update task status
        TaskHandle handle = "promotion".equals(type)
            ? promotionTasks.get(taskId)
            : syncTasks.get(taskId);
        if (handle != null && handle.status == TaskStatus.RUNNING) {
          handle.status = TaskStatus.COMPLETED;
          handle.endTime = System.currentTimeMillis();
        }

        // Cleanup task cache
        cacheManager.cleanupTask(taskId);

        log.debug("Task {} ({}) cache cleaned up", taskId, type);
      }
    };
  }

  /**
   * Get the status of a promotion task.
   */
  public TaskStatus getPromotionTaskStatus(final String taskId) {
    TaskHandle handle = promotionTasks.get(taskId);
    if (handle == null) {
      return null;
    }
    if (handle.status == TaskStatus.RUNNING && handle.future.isDone()) {
      try {
        handle.future.get();
        handle.status = TaskStatus.COMPLETED;
      }
      catch (Exception e) {
        handle.status = TaskStatus.FAILED;
      }
    }
    return handle.status;
  }

  /**
   * Get the status of a sync task.
   */
  public TaskStatus getSyncTaskStatus(final String taskId) {
    TaskHandle handle = syncTasks.get(taskId);
    if (handle == null) {
      return null;
    }
    if (handle.status == TaskStatus.RUNNING && handle.future.isDone()) {
      try {
        handle.future.get();
        handle.status = TaskStatus.COMPLETED;
      }
      catch (Exception e) {
        handle.status = TaskStatus.FAILED;
      }
    }
    return handle.status;
  }

  /**
   * Get all promotion tasks.
   */
  public Map<String, TaskHandle> getPromotionTasks() {
    return promotionTasks;
  }

  /**
   * Get all sync tasks.
   */
  public Map<String, TaskHandle> getSyncTasks() {
    return syncTasks;
  }

  /**
   * Get active (pending/running) sync tasks.
   */
  public Map<String, TaskHandle> getActiveSyncTasks() {
    Map<String, TaskHandle> active = new ConcurrentHashMap<>();
    for (Map.Entry<String, TaskHandle> entry : syncTasks.entrySet()) {
      TaskStatus status = getSyncTaskStatus(entry.getKey());
      if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
        active.put(entry.getKey(), entry.getValue());
      }
    }
    return active;
  }

  /**
   * Update promotion pool size (from system settings).
   */
  public void updatePromotionPoolSize(final int newSize) {
    if (newSize <= 0 || newSize > 50) {
      log.warn("Invalid promotion pool size: {}, ignoring", newSize);
      return;
    }
    this.promotionPoolSize = newSize;
    reconfigurePool(promotionExecutor, newSize, "promotion");
  }

  /**
   * Update sync pool size (from system settings).
   */
  public void updateSyncPoolSize(final int newSize) {
    if (newSize <= 0 || newSize > 50) {
      log.warn("Invalid sync pool size: {}, ignoring", newSize);
      return;
    }
    this.syncPoolSize = newSize;
    reconfigurePool(syncExecutor, newSize, "sync");
  }

  /**
   * Update max sync queue size.
   */
  public void updateMaxSyncQueueSize(final int newSize) {
    this.maxSyncQueueSize = Math.max(1, newSize);
    log.info("Max sync queue size updated to {}", maxSyncQueueSize);
  }

  private void reconfigurePool(final ExecutorService executor, final int newSize, final String type) {
    if (executor instanceof ThreadPoolExecutor) {
      ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
      tpe.setCorePoolSize(newSize);
      tpe.setMaximumPoolSize(newSize);
      log.info("{} pool size updated to {}", type, newSize);
    }
  }

  public int getPromotionPoolSize() { return promotionPoolSize; }
  public int getSyncPoolSize() { return syncPoolSize; }
  public int getMaxSyncQueueSize() { return maxSyncQueueSize; }

  /**
   * Check if sync queue has capacity.
   */
  public boolean hasSyncQueueCapacity() {
    int activeCount = 0;
    for (TaskHandle handle : syncTasks.values()) {
      if (handle.status == TaskStatus.PENDING || handle.status == TaskStatus.RUNNING) {
        activeCount++;
      }
    }
    return activeCount < maxSyncQueueSize;
  }

  /**
   * Graceful shutdown on plugin destroy.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down TaskExecutorService...");

    shutdownExecutor(promotionExecutor, "promotion");
    shutdownExecutor(syncExecutor, "sync");

    // Cleanup all task caches
    for (String taskId : promotionTasks.keySet()) {
      cacheManager.cleanupTask(taskId);
    }
    for (String taskId : syncTasks.keySet()) {
      cacheManager.cleanupTask(taskId);
    }

    promotionTasks.clear();
    syncTasks.clear();

    log.info("TaskExecutorService shutdown complete");
  }

  private void shutdownExecutor(final ExecutorService executor, final String name) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("{} executor did not terminate in {} seconds, forcing shutdown",
            name, SHUTDOWN_TIMEOUT_SECONDS);
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          log.error("{} executor did not terminate after forced shutdown", name);
        }
      }
    }
    catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private String generateTaskId(final String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();
  }

  // --- Inner classes ---

  /**
   * Handle for tracking a submitted task.
   */
  public static class TaskHandle {
    public final String taskId;
    public final Future<?> future;
    public volatile TaskStatus status;
    public final long startTime;
    public volatile long endTime;
    public final String sourceRepository;
    public final String sourcePath;
    public volatile String migratedToTaskId;

    public TaskHandle(String taskId, Future<?> future, TaskStatus status, long startTime) {
      this(taskId, future, status, startTime, null, null);
    }

    public TaskHandle(String taskId, Future<?> future, TaskStatus status, long startTime,
                       String sourceRepository, String sourcePath) {
      this.taskId = taskId;
      this.future = future;
      this.status = status;
      this.startTime = startTime;
      this.sourceRepository = sourceRepository;
      this.sourcePath = sourcePath;
    }
  }

  /**
   * Callback interface for promotion task completion.
   */
  public interface PromotionTaskCallback {
    String getTaskId();
    TaskStatus getStatus();
    String getErrorMessage();
  }

  /**
   * Callback interface for sync task completion.
   */
  public interface SyncTaskCallback {
    String getTaskId();
    TaskStatus getStatus();
    String getErrorMessage();
  }

  /**
   * Custom thread factory with named threads.
   */
  private static class TaskThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger(0);
    private final String namePrefix;

    TaskThreadFactory(String namePrefix) {
      this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, namePrefix + counter.incrementAndGet());
      t.setDaemon(false);
      t.setUncaughtExceptionHandler((thread, throwable) -> {
        log.error("Uncaught exception in thread {}: {}", thread.getName(), throwable.getMessage(), throwable);
      });
      return t;
    }
  }
}
