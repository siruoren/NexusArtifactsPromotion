package com.nexus.artifacts.promotion.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
  private static final int DEFAULT_MAX_PROMOTION_QUEUE_SIZE = 50;
  private static final int DEFAULT_MAX_TASK_QUEUE_RECORDS = 200;
  private static final int DEFAULT_TASK_LOG_TTL_MINUTES = 30;
  private static final int DEFAULT_TASK_TIMEOUT_MINUTES = 120;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

  // Bounded queue capacity for thread pool (prevents OOM from unbounded task accumulation)
  private static final int PROMOTION_QUEUE_CAPACITY = 100;
  private static final int SYNC_QUEUE_CAPACITY = 50;

  private volatile int promotionPoolSize = DEFAULT_PROMOTION_POOL_SIZE;
  private volatile int syncPoolSize = DEFAULT_SYNC_POOL_SIZE;
  private volatile int maxSyncQueueSize = DEFAULT_MAX_SYNC_QUEUE_SIZE;
  private volatile int maxPromotionQueueSize = DEFAULT_MAX_PROMOTION_QUEUE_SIZE;
  private volatile int maxTaskQueueRecords = DEFAULT_MAX_TASK_QUEUE_RECORDS;
  private volatile int taskLogTtlMinutes = DEFAULT_TASK_LOG_TTL_MINUTES;
  private volatile int taskTimeoutMinutes = DEFAULT_TASK_TIMEOUT_MINUTES;

  private ExecutorService promotionExecutor;
  private ExecutorService syncExecutor;
  private java.util.concurrent.ScheduledExecutorService timeoutDetector;

  private final Map<String, TaskHandle> promotionTasks = new ConcurrentHashMap<>();
  private final Map<String, TaskHandle> syncTasks = new ConcurrentHashMap<>();

  private final TaskCacheManager cacheManager;

  @Inject
  public TaskExecutorService(final TaskCacheManager cacheManager) {
    this.cacheManager = cacheManager;
    initExecutors();
  }

  private void initExecutors() {
    this.promotionExecutor = createThreadPool("promotion", promotionPoolSize, PROMOTION_QUEUE_CAPACITY);
    this.syncExecutor = createThreadPool("sync", syncPoolSize, SYNC_QUEUE_CAPACITY);
    // Start timeout detector - checks every 60 seconds
    this.timeoutDetector = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger(0);
      @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "nexus-task-timeout-detector-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
    this.timeoutDetector.scheduleAtFixedRate(this::detectTimedOutTasks, 60, 60, TimeUnit.SECONDS);
    log.info("TaskExecutorService initialized - promotion pool: {} (queue: {}), sync pool: {} (queue: {}), max sync queue: {}, task timeout: {}min",
        promotionPoolSize, PROMOTION_QUEUE_CAPACITY, syncPoolSize, SYNC_QUEUE_CAPACITY, maxSyncQueueSize, taskTimeoutMinutes);
  }

  private ExecutorService createThreadPool(final String type, final int poolSize, final int queueCapacity) {
    BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(queueCapacity);
    return new ThreadPoolExecutor(
        poolSize,
        poolSize,
        0L,
        TimeUnit.MILLISECONDS,
        workQueue,
        new TaskThreadFactory("nexus-" + type + "-task-"),
        new ThreadPoolExecutor.AbortPolicy()
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
    return submitPromotionTask(task, taskDescription, taskId, null);
  }

  /**
   * Submit a promotion task with a pre-defined taskId and username.
   */
  public String submitPromotionTask(final Callable<PromotionTaskCallback> task,
                                     final String taskDescription,
                                     final String taskId,
                                     final String username)
  {
    return submitPromotionTask(task, taskDescription, taskId, username, null, null, null);
  }

  /**
   * Submit a promotion task with full context (source/target repo and path).
   */
  public String submitPromotionTask(final Callable<PromotionTaskCallback> task,
                                     final String taskDescription,
                                     final String taskId,
                                     final String username,
                                     final String sourceRepository,
                                     final String sourcePath,
                                     final String targetRepository)
  {
    log.info("Submitting promotion task {}: {}", taskId, taskDescription);

    // Check promotion queue capacity to prevent OOM
    if (!hasPromotionQueueCapacity()) {
      throw new IllegalStateException(
          "Promotion queue is full (" + maxPromotionQueueSize
              + "). Please wait for existing tasks to complete.");
    }

    // Wrap task with timeout and error handling
    Callable<PromotionTaskCallback> wrappedTask = wrapTask(task, taskId, "promotion");

    try {
      Future<PromotionTaskCallback> future = promotionExecutor.submit(wrappedTask);
      TaskHandle handle = new TaskHandle(taskId, future, TaskStatus.RUNNING, System.currentTimeMillis(),
          sourceRepository, sourcePath, targetRepository);
      handle.username = username;
      promotionTasks.put(taskId, handle);
      return taskId;
    }
    catch (RejectedExecutionException e) {
      log.warn("Promotion task {} rejected - thread pool queue full", taskId);
      throw new IllegalStateException(
          "Promotion thread pool queue is full. Please wait for existing tasks to complete.", e);
    }
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
    return submitSyncTask(task, taskDescription, sourceRepository, sourcePath, null);
  }

  /**
   * Submit a sync task with a pre-defined taskId.
   */
  public String submitSyncTask(final Callable<SyncTaskCallback> task,
                                final String taskDescription,
                                final String sourceRepository,
                                final String sourcePath,
                                final String taskId)
  {
    return submitSyncTask(task, taskDescription, sourceRepository, sourcePath, taskId, null);
  }

  /**
   * Submit a sync task with a pre-defined taskId and username.
   */
  public String submitSyncTask(final Callable<SyncTaskCallback> task,
                                final String taskDescription,
                                final String sourceRepository,
                                final String sourcePath,
                                final String taskId,
                                final String username)
  {
    log.info("Submitting sync task {}: {}", taskId, taskDescription);

    // Check for duplicate source directory sync - cancel old task
    cancelDuplicateSyncTask(sourceRepository, sourcePath, taskId);

    Callable<SyncTaskCallback> wrappedTask = wrapTask(task, taskId, "sync");

    try {
      Future<SyncTaskCallback> future = syncExecutor.submit(wrappedTask);
      TaskHandle handle = new TaskHandle(taskId, future, TaskStatus.RUNNING, System.currentTimeMillis(),
          sourceRepository, sourcePath);
      handle.username = username;
      syncTasks.put(taskId, handle);
      return taskId;
    }
    catch (RejectedExecutionException e) {
      log.warn("Sync task {} rejected - thread pool queue full", taskId);
      throw new IllegalStateException(
          "Sync thread pool queue is full. Please wait for existing tasks to complete.", e);
    }
  }

  /**
   * Cancel duplicate sync task for the same source directory and target repository.
   * Only keeps the latest sync task when both source and target match.
   * Tasks with different targets are allowed to run in parallel.
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
        // Only cancel if target repository also matches (or both are null/default)
        boolean sameTarget = (handle.targetRepository == null && sourceRepository == null)
            || (handle.targetRepository != null && handle.targetRepository.equals(sourceRepository))
            || (handle.targetRepository != null && handle.targetRepository.equals(handle.sourceRepository));

        if (sameTarget) {
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
        final long timeoutMinutes = taskTimeoutMinutes;
        Thread timeoutWatchdog = new Thread(() -> {
          try {
            Thread.sleep(TimeUnit.MINUTES.toMillis(timeoutMinutes));
            if (!currentThread.isInterrupted()) {
              log.warn("Task {} ({}) exceeded timeout of {} minutes, interrupting",
                  taskId, type, timeoutMinutes);
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

        // Update task status - only set to COMPLETED if still RUNNING
        // (cancelTask may have already set it to CANCELLED)
        TaskHandle handle = "promotion".equals(type)
            ? promotionTasks.get(taskId)
            : syncTasks.get(taskId);
        if (handle != null && handle.status == TaskStatus.RUNNING) {
          // Check if the task was actually cancelled/interrupted
          if (Thread.currentThread().isInterrupted()) {
            handle.status = TaskStatus.CANCELLED;
          } else {
            handle.status = TaskStatus.COMPLETED;
          }
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
      catch (java.util.concurrent.CancellationException e) {
        handle.status = TaskStatus.CANCELLED;
      }
      catch (Exception e) {
        // ExecutionException means the task failed; only CancellationException means cancelled
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
      catch (java.util.concurrent.CancellationException e) {
        handle.status = TaskStatus.CANCELLED;
      }
      catch (Exception e) {
        // ExecutionException means the task failed; only CancellationException means cancelled
        handle.status = TaskStatus.FAILED;
      }
    }
    return handle.status;
  }

  /**
   * Remove a completed sync task handle to prevent memory leaks.
   * Only removes tasks that are in a terminal state (COMPLETED, FAILED, MIGRATED).
   */
  public void cleanupSyncTaskHandle(final String taskId) {
    TaskHandle handle = syncTasks.get(taskId);
    if (handle != null && handle.status != TaskStatus.PENDING && handle.status != TaskStatus.RUNNING) {
      syncTasks.remove(taskId);
      log.debug("Cleaned up sync task handle: {}", taskId);
    }
  }

  /**
   * Remove a completed promotion task handle to prevent memory leaks.
   * Only removes tasks that are in a terminal state (COMPLETED, FAILED).
   */
  public void cleanupPromotionTaskHandle(final String taskId) {
    TaskHandle handle = promotionTasks.get(taskId);
    if (handle != null && handle.status != TaskStatus.PENDING && handle.status != TaskStatus.RUNNING) {
      promotionTasks.remove(taskId);
      log.debug("Cleaned up promotion task handle: {}", taskId);
    }
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

  public void updateMaxTaskQueueRecords(final int newMax) {
    this.maxTaskQueueRecords = Math.max(1, newMax);
    log.info("Max task queue records updated to {}", maxTaskQueueRecords);
  }

  public void updateTaskLogTtlMinutes(final int newTtl) {
    this.taskLogTtlMinutes = Math.max(1, newTtl);
    log.info("Task log TTL updated to {} minutes", taskLogTtlMinutes);
  }

  public void updateTaskTimeoutMinutes(final int newTimeout) {
    this.taskTimeoutMinutes = Math.max(1, newTimeout);
    log.info("Task timeout updated to {} minutes", taskTimeoutMinutes);
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
  public int getMaxPromotionQueueSize() { return maxPromotionQueueSize; }
  public int getMaxTaskQueueRecords() { return maxTaskQueueRecords; }
  public int getTaskLogTtlMinutes() { return taskLogTtlMinutes; }
  public long getTaskLogTtlMs() { return taskLogTtlMinutes * 60 * 1000L; }
  public int getTaskTimeoutMinutes() { return taskTimeoutMinutes; }
  public long getTaskTimeoutMs() { return taskTimeoutMinutes * 60 * 1000L; }

  /**
   * Update max promotion queue size.
   */
  public void updateMaxPromotionQueueSize(final int newSize) {
    this.maxPromotionQueueSize = Math.max(1, newSize);
    log.info("Max promotion queue size updated to {}", maxPromotionQueueSize);
  }

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
   * Check if promotion queue has capacity.
   */
  public boolean hasPromotionQueueCapacity() {
    int activeCount = 0;
    for (TaskHandle handle : promotionTasks.values()) {
      if (handle.status == TaskStatus.PENDING || handle.status == TaskStatus.RUNNING) {
        activeCount++;
      }
    }
    return activeCount < maxPromotionQueueSize;
  }

  /**
   * Cancel a running or pending task by taskId.
   * Works for both promotion and sync tasks.
   * @return true if the task was found and cancelled, false otherwise
   */
  public boolean cancelTask(final String taskId) {
    if (taskId == null) return false;

    // Check promotion tasks
    TaskHandle promoHandle = promotionTasks.get(taskId);
    if (promoHandle != null) {
      if (promoHandle.status == TaskStatus.PENDING || promoHandle.status == TaskStatus.RUNNING) {
        promoHandle.future.cancel(true);
        promoHandle.status = TaskStatus.CANCELLED;
        promoHandle.endTime = System.currentTimeMillis();
        cacheManager.cleanupTask(taskId);
        log.info("Promotion task {} cancelled", taskId);
        return true;
      }
      return false;
    }

    // Check sync tasks
    TaskHandle syncHandle = syncTasks.get(taskId);
    if (syncHandle != null) {
      if (syncHandle.status == TaskStatus.PENDING || syncHandle.status == TaskStatus.RUNNING) {
        syncHandle.future.cancel(true);
        syncHandle.status = TaskStatus.CANCELLED;
        syncHandle.endTime = System.currentTimeMillis();
        cacheManager.cleanupTask(taskId);
        log.info("Sync task {} cancelled", taskId);
        return true;
      }
      return false;
    }

    log.warn("Task {} not found for cancellation", taskId);
    return false;
  }

  /**
   * Get the username of the task creator.
   * Returns null if task not found.
   */
  public String getTaskUsername(final String taskId) {
    if (taskId == null) return null;

    TaskHandle promoHandle = promotionTasks.get(taskId);
    if (promoHandle != null) {
      return promoHandle.username;
    }

    TaskHandle syncHandle = syncTasks.get(taskId);
    if (syncHandle != null) {
      return syncHandle.username;
    }

    return null;
  }

  /**
   * Check if a task exists and is cancellable (pending or running).
   */
  public boolean isTaskCancellable(final String taskId) {
    if (taskId == null) return false;

    TaskHandle promoHandle = promotionTasks.get(taskId);
    if (promoHandle != null) {
      return promoHandle.status == TaskStatus.PENDING || promoHandle.status == TaskStatus.RUNNING;
    }

    TaskHandle syncHandle = syncTasks.get(taskId);
    if (syncHandle != null) {
      return syncHandle.status == TaskStatus.PENDING || syncHandle.status == TaskStatus.RUNNING;
    }

    return false;
  }

  /**
   * Detect and cancel tasks that have exceeded the configured timeout.
   * Runs periodically to prevent tasks from running indefinitely.
   */
  private void detectTimedOutTasks() {
    long timeoutMs = getTaskTimeoutMs();
    long now = System.currentTimeMillis();

    checkTimedOutTasks(promotionTasks, timeoutMs, now, "promotion");
    checkTimedOutTasks(syncTasks, timeoutMs, now, "sync");
  }

  private void checkTimedOutTasks(final Map<String, TaskHandle> tasks, final long timeoutMs, final long now, final String type) {
    for (Map.Entry<String, TaskHandle> entry : tasks.entrySet()) {
      String taskId = entry.getKey();
      TaskHandle handle = entry.getValue();

      // Only check running or pending tasks
      if (handle.status != TaskStatus.RUNNING && handle.status != TaskStatus.PENDING) {
        continue;
      }

      long elapsed = now - handle.startTime;
      if (elapsed > timeoutMs) {
        log.warn("{} task {} has exceeded timeout ({}min elapsed, {}min limit), cancelling",
            type, taskId, elapsed / 60000, taskTimeoutMinutes);
        handle.future.cancel(true);
        handle.status = TaskStatus.CANCELLED;
        handle.endTime = now;
        cacheManager.cleanupTask(taskId);
      }
    }
  }

  /**
   * Graceful shutdown on plugin destroy.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down TaskExecutorService...");

    // Stop timeout detector
    if (timeoutDetector != null) {
      timeoutDetector.shutdownNow();
    }

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
    public final String targetRepository;
    public volatile String migratedToTaskId;
    public volatile String username;

    public TaskHandle(String taskId, Future<?> future, TaskStatus status, long startTime) {
      this(taskId, future, status, startTime, null, null, null);
    }

    public TaskHandle(String taskId, Future<?> future, TaskStatus status, long startTime,
                       String sourceRepository, String sourcePath) {
      this(taskId, future, status, startTime, sourceRepository, sourcePath, sourceRepository);
    }

    public TaskHandle(String taskId, Future<?> future, TaskStatus status, long startTime,
                       String sourceRepository, String sourcePath, String targetRepository) {
      this.taskId = taskId;
      this.future = future;
      this.status = status;
      this.startTime = startTime;
      this.sourceRepository = sourceRepository;
      this.sourcePath = sourcePath;
      this.targetRepository = targetRepository;
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
