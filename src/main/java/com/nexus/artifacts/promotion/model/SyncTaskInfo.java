package com.nexus.artifacts.promotion.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sync task information DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncTaskInfo {

  private String taskId;
  private String sourceRepository;
  private String targetRepository;
  private String path;
  private boolean isDirectory;
  private String format;
  private TaskStatus status;
  private List<SyncFileDetail> fileDetails;
  private long startTime;
  private long endTime;
  private String username;
  private String result;
  private String errorMessage;
  private String migratedFromTaskId;

  public SyncTaskInfo() {
    this.fileDetails = new ArrayList<>();
  }

  public String getTaskId() { return taskId; }
  public void setTaskId(String taskId) { this.taskId = taskId; }

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getTargetRepository() { return targetRepository; }
  public void setTargetRepository(String targetRepository) { this.targetRepository = targetRepository; }

  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }

  public boolean isDirectory() { return isDirectory; }
  public void setDirectory(boolean directory) { isDirectory = directory; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  public TaskStatus getStatus() { return status; }
  public void setStatus(TaskStatus status) { this.status = status; }

  public List<SyncFileDetail> getFileDetails() { return fileDetails; }
  public void setFileDetails(List<SyncFileDetail> fileDetails) { this.fileDetails = fileDetails; }

  public long getStartTime() { return startTime; }
  public void setStartTime(long startTime) { this.startTime = startTime; }

  public long getEndTime() { return endTime; }
  public void setEndTime(long endTime) { this.endTime = endTime; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }

  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

  public String getMigratedFromTaskId() { return migratedFromTaskId; }
  public void setMigratedFromTaskId(String migratedFromTaskId) { this.migratedFromTaskId = migratedFromTaskId; }

  /**
   * Detail of a single file in a sync task.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class SyncFileDetail {
    private String path;
    private String type; // "directory", "file", "image"
    private String status; // "pending", "success", "failed", "skipped"
    private String errorMessage;
    private String remoteMd5;
    private String localMd5;

    public SyncFileDetail() {}

    public SyncFileDetail(String path, String type) {
      this.path = path;
      this.type = type;
      this.status = "pending";
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getRemoteMd5() { return remoteMd5; }
    public void setRemoteMd5(String remoteMd5) { this.remoteMd5 = remoteMd5; }

    public String getLocalMd5() { return localMd5; }
    public void setLocalMd5(String localMd5) { this.localMd5 = localMd5; }
  }
}
