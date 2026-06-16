package com.nexus.artifacts.promotion.exception;

/**
 * Exception thrown when a task execution fails.
 */
public class TaskExecutionException extends RuntimeException {

  private final String taskId;
  private final String taskType;

  public TaskExecutionException(final String taskId, final String taskType, final String message) {
    super(String.format("Task '%s' (%s) failed: %s", taskId, taskType, message));
    this.taskId = taskId;
    this.taskType = taskType;
  }

  public TaskExecutionException(final String taskId, final String taskType, final String message, final Throwable cause) {
    super(String.format("Task '%s' (%s) failed: %s", taskId, taskType, message), cause);
    this.taskId = taskId;
    this.taskType = taskType;
  }

  public String getTaskId() { return taskId; }
  public String getTaskType() { return taskType; }
}
