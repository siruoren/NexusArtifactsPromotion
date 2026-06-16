package com.nexus.artifacts.promotion.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages per-file write locks to prevent concurrent write conflicts.
 * Ensures that only one task can write to a specific repository+path at a time,
 * preventing file corruption from overlapping HTTP PUT or sync operations.
 */
@Named
@Singleton
public class FileWriteLockManager {

  private static final Logger log = LoggerFactory.getLogger(FileWriteLockManager.class);

  private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

  /**
   * Execute an operation with an exclusive file lock for the given repository and path.
   * Only one thread can hold the lock for a given repository+path combination at a time.
   *
   * @param repository the target repository name
   * @param filePath   the file path within the repository
   * @param operation  the operation to execute under the lock
   * @param <T>        return type of the operation
   * @return the result of the operation
   * @throws Exception if the operation throws
   */
  public <T> T executeWithFileLock(final String repository, final String filePath,
                                   final LockedOperation<T> operation) throws Exception {
    String key = repository + ":" + filePath;
    ReentrantLock lock = fileLocks.computeIfAbsent(key, k -> new ReentrantLock());

    lock.lock();
    try {
      return operation.execute();
    }
    finally {
      lock.unlock();
      // Cleanup lock entry if no other threads are waiting
      if (!lock.hasQueuedThreads()) {
        fileLocks.remove(key, lock);
      }
    }
  }

  /**
   * Execute a void operation with an exclusive file lock.
   *
   * @param repository the target repository name
   * @param filePath   the file path within the repository
   * @param operation  the operation to execute under the lock
   * @throws Exception if the operation throws
   */
  public void executeWithFileLockVoid(final String repository, final String filePath,
                                      final LockedVoidOperation operation) throws Exception {
    String key = repository + ":" + filePath;
    ReentrantLock lock = fileLocks.computeIfAbsent(key, k -> new ReentrantLock());

    lock.lock();
    try {
      operation.execute();
    }
    finally {
      lock.unlock();
      if (!lock.hasQueuedThreads()) {
        fileLocks.remove(key, lock);
      }
    }
  }

  /**
   * Get the current number of active file locks (for monitoring).
   */
  public int getActiveLockCount() {
    return fileLocks.size();
  }

  /**
   * Functional interface for operations that return a value.
   */
  @FunctionalInterface
  public interface LockedOperation<T> {
    T execute() throws Exception;
  }

  /**
   * Functional interface for operations that return void.
   */
  @FunctionalInterface
  public interface LockedVoidOperation {
    void execute() throws Exception;
  }
}
