package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for executing operations with configurable retry and exponential backoff.
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable max retry attempts (default: 3)</li>
 *   <li>Exponential backoff with jitter: baseDelay * 2^attempt + random jitter</li>
 *   <li>Retryable exception filtering — only retries on transient failures</li>
 *   <li>Supports both {@link Callable} and {@link Runnable} style operations</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   String result = RetryableOperation.execute("download blob", () -> {
 *     // operation that may throw IOException
 *     return downloadBlob(url);
 *   }, 3);
 * </pre>
 */
public class RetryableOperation {

  private static final Logger log = LoggerFactory.getLogger(RetryableOperation.class);

  /** Default maximum retry attempts */
  public static final int DEFAULT_MAX_RETRIES = 3;

  /** Base delay in milliseconds for exponential backoff */
  private static volatile long baseDelayMs = 1000;

  /** Maximum delay cap in milliseconds */
  private static volatile long maxDelayMs = 30_000;

  /** Jitter range in milliseconds (random 0~jitter added to delay) */
  private static volatile long jitterMs = 500;

  /**
   * Update retry strategy parameters.
   */
  public static void configure(final long baseDelay, final long maxDelay, final long jitter) {
    baseDelayMs = Math.max(100, baseDelay);
    maxDelayMs = Math.max(1000, maxDelay);
    jitterMs = Math.max(0, jitter);
    log.info("Retry strategy configured: baseDelay={}ms, maxDelay={}ms, jitter={}ms",
        baseDelayMs, maxDelayMs, jitterMs);
  }

  public static long getBaseDelayMs() { return baseDelayMs; }
  public static long getMaxDelayMs() { return maxDelayMs; }
  public static long getJitterMs() { return jitterMs; }

  /**
   * Execute a callable with retry and exponential backoff.
   *
   * @param operationName descriptive name for logging
   * @param callable      the operation to execute
   * @param maxRetries    maximum number of retry attempts (0 = no retry)
   * @param <T>           return type
   * @return the result of the callable
   * @throws Exception if all retries are exhausted
   */
  public static <T> T execute(String operationName, RetryableCallable<T> callable, int maxRetries)
      throws Exception
  {
    Exception lastException = null;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return callable.call();
      }
      catch (Exception e) {
        lastException = e;

        if (!isRetryable(e) || attempt >= maxRetries) {
          if (attempt > 0) {
            log.warn("[RETRY] {} failed after {} attempts, giving up: {}",
                operationName, attempt + 1, e.getMessage());
          }
          throw e;
        }

        long delay = calculateDelay(attempt);
        log.warn("[RETRY] {} failed (attempt {}/{}), retrying in {}ms: {}",
            operationName, attempt + 1, maxRetries + 1, delay, e.getMessage());

        Thread.sleep(delay);
      }
    }

    throw lastException;
  }

  /**
   * Execute a callable with default retry count.
   */
  public static <T> T execute(String operationName, RetryableCallable<T> callable) throws Exception {
    return execute(operationName, callable, DEFAULT_MAX_RETRIES);
  }

  /**
   * Execute a runnable-style operation with retry (no return value).
   */
  public static void executeRun(String operationName, RetryableRunnable runnable, int maxRetries)
      throws Exception
  {
    execute(operationName, () -> {
      runnable.run();
      return null;
    }, maxRetries);
  }

  /**
   * Execute a runnable-style operation with default retry count.
   */
  public static void executeRun(String operationName, RetryableRunnable runnable) throws Exception {
    executeRun(operationName, runnable, DEFAULT_MAX_RETRIES);
  }

  /**
   * Determine if an exception is retryable.
   *
   * <p>Retryable conditions:
   * <ul>
   *   <li>{@link IOException} — network/transient failures</li>
   *   <li>{@link RuntimeException} wrapping an IOException</li>
   *   <li>HTTP 429 (Too Many Requests), 502, 503, 504 errors</li>
   * </ul>
   *
   * <p>Non-retryable conditions:
   * <ul>
   *   <li>{@link IllegalArgumentException} — programming/parameter errors</li>
   *   <li>HTTP 4xx client errors (except 429)</li>
   *   <li>{@link SecurityException}</li>
   * </ul>
   */
  public static boolean isRetryable(Exception e) {
    // IllegalArgumentException — never retry (caller error)
    if (e instanceof IllegalArgumentException) {
      return false;
    }

    // SecurityException — never retry
    if (e instanceof SecurityException) {
      return false;
    }

    // IOException — generally retryable (network failures)
    if (e instanceof IOException) {
      // Check for HTTP error codes embedded in message
      return !isNonRetryableHttpError(e.getMessage());
    }

    // RuntimeException — check cause
    if (e instanceof RuntimeException) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        return isRetryable((Exception) cause);
      }
      if (cause instanceof IllegalArgumentException) {
        return false;
      }
      // Other RuntimeExceptions — check HTTP status in message
      return !isNonRetryableHttpError(e.getMessage());
    }

    // Default: don't retry unknown exceptions
    return false;
  }

  /**
   * Check if an error message contains a non-retryable HTTP status code.
   * HTTP 4xx (except 429 Too Many Requests) are client errors that won't
   * be fixed by retrying.
   */
  private static boolean isNonRetryableHttpError(String message) {
    if (message == null) return false;

    // Check for HTTP status codes in error messages like "HTTP 401", "HTTP 403", "HTTP 404"
    // 429 (Too Many Requests) IS retryable
    // 5xx server errors are retryable
    // 400 (Bad Request), 401 (Unauthorized), 403 (Forbidden), 404 (Not Found) are NOT retryable
    if (message.contains("HTTP 400") || message.contains("HTTP 401") ||
        message.contains("HTTP 403") || message.contains("HTTP 404") ||
        message.contains("HTTP 405") || message.contains("HTTP 409") ||
        message.contains("HTTP 422")) {
      return true;
    }

    return false;
  }

  /**
   * Calculate delay for exponential backoff with jitter.
   * Formula: min(maxDelay, baseDelay * 2^attempt) + random(0, jitter)
   */
  private static long calculateDelay(int attempt) {
    long exponentialDelay = (long) (baseDelayMs * Math.pow(2, attempt));
    long cappedDelay = Math.min(exponentialDelay, maxDelayMs);
    long jitter = ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
    return cappedDelay + jitter;
  }

  /**
   * Check if an HTTP response code indicates a retryable server error.
   */
  public static boolean isRetryableHttpCode(int responseCode) {
    // 429 Too Many Requests — retryable with backoff
    if (responseCode == 429) return true;
    // 5xx Server Errors — retryable
    if (responseCode >= 500 && responseCode < 600) return true;
    // Everything else (2xx, 3xx, 4xx) — not retryable
    return false;
  }

  // --- Functional interfaces ---

  /**
   * Callable that can throw checked exceptions.
   */
  @FunctionalInterface
  public interface RetryableCallable<T> {
    T call() throws Exception;
  }

  /**
   * Runnable that can throw checked exceptions.
   */
  @FunctionalInterface
  public interface RetryableRunnable {
    void run() throws Exception;
  }
}
