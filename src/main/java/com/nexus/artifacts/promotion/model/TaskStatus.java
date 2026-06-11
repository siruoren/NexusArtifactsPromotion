package com.nexus.artifacts.promotion.model;

/**
 * Task status enum for both promotion and sync tasks.
 */
public enum TaskStatus {
  PENDING("pending"),
  RUNNING("running"),
  COMPLETED("completed"),
  FAILED("failed"),
  CANCELLED("cancelled"),
  MIGRATED("migrated");

  private final String value;

  TaskStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static TaskStatus fromValue(String value) {
    if (value == null) {
      return PENDING;
    }
    for (TaskStatus status : values()) {
      if (status.value.equalsIgnoreCase(value)) {
        return status;
      }
    }
    return PENDING;
  }
}
