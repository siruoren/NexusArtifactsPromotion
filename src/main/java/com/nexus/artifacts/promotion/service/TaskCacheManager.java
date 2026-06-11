package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages per-task cache directories for promotion and sync operations.
 * Features:
 * - Isolated cache directories per task ID
 * - Automatic cleanup on task completion
 * - Memory leak prevention
 * - Configurable base cache directory
 */
@Named
@Singleton
public class TaskCacheManager {

  private static final Logger log = LoggerFactory.getLogger(TaskCacheManager.class);

  private static final String DEFAULT_CACHE_BASE = System.getProperty("karaf.data", "/tmp/nexus")
      + "/promotion-sync-cache";

  private final Path cacheBasePath;
  private final Map<String, Path> taskCacheDirs = new ConcurrentHashMap<>();

  @Inject
  public TaskCacheManager() {
    this.cacheBasePath = Paths.get(DEFAULT_CACHE_BASE);
    initCacheDirectory();
  }

  private void initCacheDirectory() {
    try {
      Files.createDirectories(cacheBasePath);
      log.info("Task cache directory initialized at: {}", cacheBasePath);
    }
    catch (IOException e) {
      log.error("Failed to create cache directory: {}", cacheBasePath, e);
    }
  }

  /**
   * Create an isolated cache directory for a task.
   *
   * @param taskId the task ID
   * @return the path to the task's cache directory
   */
  public Path createTaskCache(final String taskId) {
    validateTaskId(taskId);

    Path taskDir = cacheBasePath.resolve(sanitizeTaskId(taskId));
    try {
      Files.createDirectories(taskDir);
      taskCacheDirs.put(taskId, taskDir);
      log.debug("Created cache directory for task {}: {}", taskId, taskDir);
      return taskDir;
    }
    catch (IOException e) {
      log.error("Failed to create cache directory for task {}: {}", taskId, e.getMessage());
      throw new RuntimeException("Failed to create task cache directory", e);
    }
  }

  /**
   * Get the cache directory for a task.
   *
   * @param taskId the task ID
   * @return the path to the task's cache directory, or null if not found
   */
  public Path getTaskCache(final String taskId) {
    validateTaskId(taskId);
    return taskCacheDirs.get(taskId);
  }

  /**
   * Store a file in the task's cache directory.
   *
   * @param taskId   the task ID
   * @param relativePath the relative path within the cache
   * @param data     the input stream data
   * @return the full path where the file was stored
   */
  public Path storeFile(final String taskId, final String relativePath, final InputStream data) {
    validateTaskId(taskId);
    validateRelativePath(relativePath);

    Path taskDir = getOrCreateTaskCache(taskId);
    Path filePath = taskDir.resolve(relativePath);

    try {
      Files.createDirectories(filePath.getParent());
      Files.copy(data, filePath, StandardCopyOption.REPLACE_EXISTING);
      log.debug("Stored file in cache for task {}: {}", taskId, relativePath);
      return filePath;
    }
    catch (IOException e) {
      log.error("Failed to store file in cache for task {}: {}", taskId, e.getMessage());
      throw new RuntimeException("Failed to store file in task cache", e);
    }
  }

  /**
   * Retrieve a file from the task's cache directory.
   *
   * @param taskId   the task ID
   * @param relativePath the relative path within the cache
   * @return an input stream of the cached file
   */
  public InputStream retrieveFile(final String taskId, final String relativePath) {
    validateTaskId(taskId);
    validateRelativePath(relativePath);

    Path taskDir = taskCacheDirs.get(taskId);
    if (taskDir == null) {
      throw new IllegalArgumentException("No cache directory found for task: " + taskId);
    }

    Path filePath = taskDir.resolve(relativePath);
    if (!Files.exists(filePath)) {
      throw new IllegalArgumentException("File not found in cache: " + relativePath);
    }

    try {
      return Files.newInputStream(filePath);
    }
    catch (IOException e) {
      log.error("Failed to retrieve file from cache for task {}: {}", taskId, e.getMessage());
      throw new RuntimeException("Failed to retrieve file from task cache", e);
    }
  }

  /**
   * Cleanup the cache directory for a specific task.
   * Called automatically when a task completes.
   */
  public void cleanupTask(final String taskId) {
    validateTaskId(taskId);

    Path taskDir = taskCacheDirs.remove(taskId);
    if (taskDir != null && Files.exists(taskDir)) {
      deleteRecursively(taskDir);
      log.info("Cleaned up cache for task {}: {}", taskId, taskDir);
    }
  }

  /**
   * Cleanup all task caches. Called on shutdown.
   */
  @PreDestroy
  public void cleanupAll() {
    log.info("Cleaning up all task caches...");
    for (Map.Entry<String, Path> entry : taskCacheDirs.entrySet()) {
      deleteRecursively(entry.getValue());
      log.debug("Cleaned up cache for task: {}", entry.getKey());
    }
    taskCacheDirs.clear();

    // Also clean up any leftover directories on disk
    if (Files.exists(cacheBasePath)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheBasePath)) {
        for (Path path : stream) {
          if (Files.isDirectory(path)) {
            deleteRecursively(path);
          }
        }
      }
      catch (IOException e) {
        log.warn("Failed to scan cache directory for cleanup: {}", e.getMessage());
      }
    }

    log.info("All task caches cleaned up");
  }

  /**
   * Get the total size of all task caches.
   */
  public long getTotalCacheSize() {
    long totalSize = 0;
    for (Path taskDir : taskCacheDirs.values()) {
      totalSize += getDirectorySize(taskDir);
    }
    return totalSize;
  }

  /**
   * Get the number of active task caches.
   */
  public int getActiveCacheCount() {
    return taskCacheDirs.size();
  }

  private Path getOrCreateTaskCache(final String taskId) {
    return taskCacheDirs.computeIfAbsent(taskId, id -> {
      Path dir = cacheBasePath.resolve(sanitizeTaskId(id));
      try {
        Files.createDirectories(dir);
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to create task cache directory", e);
      }
      return dir;
    });
  }

  private void deleteRecursively(final Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
          for (Path child : stream) {
            deleteRecursively(child);
          }
        }
      }
      Files.deleteIfExists(path);
    }
    catch (IOException e) {
      log.warn("Failed to delete path {}: {}", path, e.getMessage());
    }
  }

  private long getDirectorySize(final Path path) {
    try {
      if (Files.isDirectory(path)) {
        long size = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
          for (Path child : stream) {
            size += getDirectorySize(child);
          }
        }
        return size;
      }
      else {
        return Files.size(path);
      }
    }
    catch (IOException e) {
      return 0;
    }
  }

  /**
   * Validate task ID to prevent path traversal.
   */
  private void validateTaskId(final String taskId) {
    if (taskId == null || taskId.isEmpty()) {
      throw new IllegalArgumentException("taskId is required");
    }
    if (taskId.contains("..") || taskId.contains("/") || taskId.contains("\\") || taskId.contains("\0")) {
      throw new IllegalArgumentException("Invalid taskId: path traversal detected");
    }
  }

  /**
   * Validate relative path to prevent path traversal.
   */
  private void validateRelativePath(final String relativePath) {
    if (relativePath == null || relativePath.isEmpty()) {
      throw new IllegalArgumentException("relativePath is required");
    }
    if (relativePath.contains("..") || relativePath.contains("\0")) {
      throw new IllegalArgumentException("Invalid relativePath: path traversal detected");
    }
  }

  /**
   * Sanitize task ID for use as directory name.
   */
  private String sanitizeTaskId(final String taskId) {
    return taskId.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
