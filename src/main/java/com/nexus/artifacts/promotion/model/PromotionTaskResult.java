package com.nexus.artifacts.promotion.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for a promotion task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionTaskResult {

  private String taskId;
  private String sourceRepository;
  private String targetRepository;
  private TaskStatus status;
  private List<FileItem> items;
  private String username;
  private long startTime;
  private long endTime;
  private String errorMessage;

  public PromotionTaskResult() {
    this.items = new ArrayList<>();
  }

  public String getTaskId() { return taskId; }
  public void setTaskId(String taskId) { this.taskId = taskId; }

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getTargetRepository() { return targetRepository; }
  public void setTargetRepository(String targetRepository) { this.targetRepository = targetRepository; }

  public TaskStatus getStatus() { return status; }
  public void setStatus(TaskStatus status) { this.status = status; }

  public List<FileItem> getItems() { return items; }
  public void setItems(List<FileItem> items) { this.items = items; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public long getStartTime() { return startTime; }
  public void setStartTime(long startTime) { this.startTime = startTime; }

  public long getEndTime() { return endTime; }
  public void setEndTime(long endTime) { this.endTime = endTime; }

  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

  /**
   * Represents a single file/directory item in the promotion result.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class FileItem {
    private String path;
    private FileAction action;
    private String type; // "directory", "file", "image"
    private String status;
    private String errorMessage;

    public FileItem() {}

    public FileItem(String path, FileAction action, String type) {
      this.path = path;
      this.action = action;
      this.type = type;
      this.status = "success";
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public FileAction getAction() { return action; }
    public void setAction(FileAction action) { this.action = action; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
  }

  /**
   * Action taken on a file during promotion.
   */
  public enum FileAction {
    CREATED,
    UPDATED
  }
}
