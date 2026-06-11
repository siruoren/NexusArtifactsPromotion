package com.nexus.artifacts.promotion.exception;

/**
 * Exception thrown when a task times out.
 */
public class TaskTimeoutException extends RuntimeException {

  private final String taskId;
  private final long timeoutMinutes;

  public TaskTimeoutException(final String taskId, final long timeoutMinutes) {
    super(String.format("Task '%s' exceeded timeout of %d minutes", taskId, timeoutMinutes));
    this.taskId = taskId;
    this.timeoutMinutes = timeoutMinutes;
  }

  public String getTaskId() { return taskId; }
  public long getTimeoutMinutes() { return timeoutMinutes; }
}
