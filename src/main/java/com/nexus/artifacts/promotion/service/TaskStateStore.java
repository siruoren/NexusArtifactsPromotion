package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;

/**
 * Persistent storage for task state, surviving Nexus restarts.
 *
 * <p>Task state is serialized to JSON files on disk.
 * On startup, previously persisted task states are loaded back into memory
 * so that users can still query their task results after a restart.
 *
 * <p>Features:
 * <ul>
 *   <li>Automatic persistence of completed/failed task states</li>
 *   <li>Automatic loading on startup</li>
 *   <li>TTL-based cleanup of expired entries</li>
 *   <li>Thread-safe: uses ConcurrentHashMap for in-memory index</li>
 * </ul>
 */
@Named
@Singleton
public class TaskStateStore {

  private static final Logger log = LoggerFactory.getLogger(TaskStateStore.class);

  private static final String DEFAULT_STORE_BASE = System.getProperty("karaf.data", "/tmp/nexus")
      + "/promotion-task-state";

  private static final long TASK_RESULT_TTL_MS = 30 * 60 * 1000L; // 30 minutes

  private final Path storeBasePath;
  private final ObjectMapper objectMapper;

  /** In-memory index of persisted promotion task results */
  private final Map<String, PromotionTaskResult> promotionResults = new ConcurrentHashMap<>();

  /** In-memory index of persisted sync task infos */
  private final Map<String, SyncTaskInfo> syncTaskInfos = new ConcurrentHashMap<>();

  @Inject
  public TaskStateStore() {
    this.storeBasePath = Paths.get(DEFAULT_STORE_BASE);
    this.objectMapper = new ObjectMapper();
    this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    this.objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  @PostConstruct
  public void init() {
    try {
      Files.createDirectories(storeBasePath);
      Files.createDirectories(storeBasePath.resolve("promotion"));
      Files.createDirectories(storeBasePath.resolve("sync"));
      loadPersistedStates();
      log.info("TaskStateStore initialized at: {}", storeBasePath);
    }
    catch (IOException e) {
      log.error("Failed to initialize TaskStateStore: {}", e.getMessage(), e);
    }
  }

  // ==================== Promotion Task State ====================

  /**
   * Persist a promotion task result.
   */
  public void savePromotionResult(PromotionTaskResult result) {
    if (result == null || result.getTaskId() == null) return;

    promotionResults.put(result.getTaskId(), result);

    // Only persist terminal states
    String status = result.getStatus();
    if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
      persistToFile("promotion", result.getTaskId(), result);
    }
  }

  /**
   * Get a persisted promotion task result.
   */
  public PromotionTaskResult getPromotionResult(String taskId) {
    return promotionResults.get(taskId);
  }

  /**
   * Remove a promotion task result.
   */
  public void removePromotionResult(String taskId) {
    promotionResults.remove(taskId);
    deleteFile("promotion", taskId);
  }

  // ==================== Sync Task State ====================

  /**
   * Persist a sync task info.
   */
  public void saveSyncTaskInfo(SyncTaskInfo info) {
    if (info == null || info.getTaskId() == null) return;

    syncTaskInfos.put(info.getTaskId(), info);

    // Only persist terminal states
    if (info.getStatus() != null &&
        (info.getStatus() == com.nexus.artifacts.promotion.model.TaskStatus.COMPLETED ||
            info.getStatus() == com.nexus.artifacts.promotion.model.TaskStatus.FAILED)) {
      persistToFile("sync", info.getTaskId(), info);
    }
  }

  /**
   * Get a persisted sync task info.
   */
  public SyncTaskInfo getSyncTaskInfo(String taskId) {
    return syncTaskInfos.get(taskId);
  }

  /**
   * Remove a sync task info.
   */
  public void removeSyncTaskInfo(String taskId) {
    syncTaskInfos.remove(taskId);
    deleteFile("sync", taskId);
  }

  // ==================== Cleanup ====================

  /**
   * Clean up expired task states (both in-memory and on disk).
   */
  public void cleanupExpired() {
    long now = System.currentTimeMillis();
    List<String> expiredIds = new ArrayList<>();

    // Check promotion results
    for (Map.Entry<String, PromotionTaskResult> entry : promotionResults.entrySet()) {
      PromotionTaskResult r = entry.getValue();
      if (("COMPLETED".equals(r.getStatus()) || "FAILED".equals(r.getStatus()))
          && r.getEndTime() > 0
          && (now - r.getEndTime()) > TASK_RESULT_TTL_MS) {
        expiredIds.add(entry.getKey());
      }
    }
    for (String id : expiredIds) {
      promotionResults.remove(id);
      deleteFile("promotion", id);
    }

    // Check sync task infos
    expiredIds.clear();
    for (Map.Entry<String, SyncTaskInfo> entry : syncTaskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      if ((info.getStatus() == com.nexus.artifacts.promotion.model.TaskStatus.COMPLETED ||
          info.getStatus() == com.nexus.artifacts.promotion.model.TaskStatus.FAILED)
          && info.getEndTime() > 0
          && (now - info.getEndTime()) > TASK_RESULT_TTL_MS) {
        expiredIds.add(entry.getKey());
      }
    }
    for (String id : expiredIds) {
      syncTaskInfos.remove(id);
      deleteFile("sync", id);
    }
  }

  // ==================== Internal ====================

  private void loadPersistedStates() {
    // Load promotion task states
    Path promotionDir = storeBasePath.resolve("promotion");
    loadStatesFromDirectory(promotionDir, "promotion");

    // Load sync task states
    Path syncDir = storeBasePath.resolve("sync");
    loadStatesFromDirectory(syncDir, "sync");

    log.info("Loaded {} promotion results and {} sync task infos from disk",
        promotionResults.size(), syncTaskInfos.size());
  }

  private void loadStatesFromDirectory(Path dir, String type) {
    if (!Files.exists(dir)) return;

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
      for (Path file : stream) {
        try {
          String json = new String(Files.readAllBytes(file), "UTF-8");
          if ("promotion".equals(type)) {
            PromotionTaskResult result = objectMapper.readValue(json, PromotionTaskResult.class);
            if (result != null && result.getTaskId() != null) {
              promotionResults.put(result.getTaskId(), result);
            }
          }
          else if ("sync".equals(type)) {
            SyncTaskInfo info = objectMapper.readValue(json, SyncTaskInfo.class);
            if (info != null && info.getTaskId() != null) {
              syncTaskInfos.put(info.getTaskId(), info);
            }
          }
        }
        catch (Exception e) {
          log.warn("Failed to load task state from {}: {}", file, e.getMessage());
        }
      }
    }
    catch (IOException e) {
      log.warn("Failed to scan directory {}: {}", dir, e.getMessage());
    }
  }

  private void persistToFile(String type, String taskId, Object data) {
    try {
      Path dir = storeBasePath.resolve(type);
      Files.createDirectories(dir);

      String safeId = sanitizeId(taskId);
      Path file = dir.resolve(safeId + ".json");
      String json = objectMapper.writeValueAsString(data);
      try (BufferedWriter writer = Files.newBufferedWriter(file)) {
        writer.write(json);
      }
      log.debug("Persisted {} task state to: {}", type, file);
    }
    catch (Exception e) {
      log.warn("Failed to persist {} task state for {}: {}", type, taskId, e.getMessage());
    }
  }

  private void deleteFile(String type, String taskId) {
    try {
      String safeId = sanitizeId(taskId);
      Path file = storeBasePath.resolve(type).resolve(safeId + ".json");
      Files.deleteIfExists(file);
    }
    catch (Exception e) {
      log.debug("Failed to delete task state file for {}: {}", taskId, e.getMessage());
    }
  }

  private String sanitizeId(String taskId) {
    return taskId.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
