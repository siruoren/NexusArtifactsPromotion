package com.nexus.artifacts.promotion.exception;

/**
 * Exception thrown when the sync queue is at capacity.
 */
public class QueueFullException extends RuntimeException {

  private final int maxSize;
  private final int currentSize;

  public QueueFullException(final int maxSize, final int currentSize) {
    super(String.format("Sync queue is full (max: %d, current: %d). Please wait for existing tasks to complete.",
        maxSize, currentSize));
    this.maxSize = maxSize;
    this.currentSize = currentSize;
  }

  public int getMaxSize() { return maxSize; }
  public int getCurrentSize() { return currentSize; }
}
