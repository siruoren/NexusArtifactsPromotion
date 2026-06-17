package com.nexus.artifacts.promotion.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pooled HTTP client utility for Nexus Artifact Promotion.
 *
 * <p>Leverages JDK's built-in HTTP Keep-Alive connection pooling:
 * <ul>
 *   <li>JDK maintains a pool of idle connections per destination (host:port)</li>
 *   <li>Connections are reused when Keep-Alive is enabled (default in HTTP/1.1)</li>
 *   <li>Pool size controlled by {@code http.maxConnections} system property (default: 5)</li>
 *   <li>Idle connections expire after {@code http.keepAliveTimeout} seconds (default: 60s)</li>
 * </ul>
 *
 * <p>This class provides:
 * <ul>
 *   <li>Centralized connection timeout and read timeout configuration</li>
 *   <li>Connection reuse statistics tracking</li>
 *   <li>Chunked streaming mode for large uploads</li>
 *   <li>Consistent error handling and response reading</li>
 * </ul>
 */
@Named
@Singleton
public class HttpClientPool {

  private static final Logger log = LoggerFactory.getLogger(HttpClientPool.class);

  // Default timeout values (can be overridden via configure())
  private static final int DEFAULT_CONNECT_TIMEOUT_MS = 30_000;
  private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
  private static final int DEFAULT_CHUNK_SIZE = 8192;
  private static final int DEFAULT_MAX_CONNECTIONS = 20;

  private volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
  private volatile int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
  private volatile int chunkSize = DEFAULT_CHUNK_SIZE;

  // Statistics
  private final AtomicInteger totalRequests = new AtomicInteger(0);
  private final AtomicInteger reusedConnections = new AtomicInteger(0);

  /**
   * Configure connection pool parameters.
   * Increases JDK's max connections per destination for high-throughput scenarios.
   */
  public HttpClientPool() {
    // Increase JDK's HTTP connection pool size for concurrent promotion/sync
    String maxConn = System.getProperty("http.maxConnections");
    if (maxConn == null) {
      System.setProperty("http.maxConnections", String.valueOf(DEFAULT_MAX_CONNECTIONS));
      log.info("Set http.maxConnections={}", DEFAULT_MAX_CONNECTIONS);
    }
    else {
      log.info("Using existing http.maxConnections={}", maxConn);
    }

    // Ensure HTTP Keep-Alive is enabled (default)
    String keepAlive = System.getProperty("http.keepAlive");
    if (keepAlive == null || !"false".equals(keepAlive)) {
      System.setProperty("http.keepAlive", "true");
    }
  }

  /**
   * Update timeout and chunk size configuration.
   */
  public void configure(final int connectTimeoutMs, final int readTimeoutMs, final int chunkSize) {
    this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
    this.readTimeoutMs = Math.max(1000, readTimeoutMs);
    this.chunkSize = Math.max(1024, chunkSize);
    log.info("HttpClientPool configured: connectTimeout={}ms, readTimeout={}ms, chunkSize={}",
        this.connectTimeoutMs, this.readTimeoutMs, this.chunkSize);
  }

  /**
   * Execute an HTTP GET request with connection pooling.
   *
   * @param url the URL to GET
   * @param headers optional request headers
   * @return HttpResponse with status code, body, and headers
   */
  public HttpResponse get(final String url, final Map<String, String> headers) throws IOException {
    totalRequests.incrementAndGet();
    HttpURLConnection conn = null;
    InputStream inputStream = null;
    try {
      conn = openConnection(url, "GET", headers);

      int statusCode = conn.getResponseCode();
      Map<String, String> responseHeaders = extractResponseHeaders(conn);

      String body;
      if (statusCode >= 200 && statusCode < 300) {
        inputStream = conn.getInputStream();
        body = readStream(inputStream);
      }
      else {
        body = readErrorResponse(conn);
      }

      return new HttpResponse(statusCode, body, responseHeaders);
    }
    finally {
      if (inputStream != null) {
        try { inputStream.close(); } catch (IOException ignored) {}
      }
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Execute an HTTP GET request with custom Accept header for Docker manifests.
   */
  public HttpResponse getWithAccept(final String url, final String acceptHeader,
                                     final Map<String, String> extraHeaders) throws IOException {
    Map<String, String> headers = new HashMap<>();
    if (extraHeaders != null) {
      headers.putAll(extraHeaders);
    }
    if (acceptHeader != null) {
      headers.put("Accept", acceptHeader);
    }
    return get(url, headers);
  }

  /**
   * Execute an HTTP PUT request with chunked streaming for large uploads.
   *
   * @param url the URL to PUT
   * @param data the data to upload
   * @param contentType the Content-Type header
   * @param headers optional additional request headers
   * @return HttpResponse with status code and response body
   */
  public HttpResponse put(final String url, final byte[] data, final String contentType,
                           final Map<String, String> headers) throws IOException {
    totalRequests.incrementAndGet();
    HttpURLConnection conn = null;
    InputStream responseStream = null;
    try {
      conn = openConnection(url, "PUT", headers);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", contentType);
      // Use chunked streaming mode for efficient memory usage
      conn.setChunkedStreamingMode(chunkSize);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(data);
        os.flush();
      }

      int statusCode = conn.getResponseCode();
      Map<String, String> responseHeaders = extractResponseHeaders(conn);

      String body;
      if (statusCode >= 200 && statusCode < 300) {
        responseStream = conn.getInputStream();
        body = readStream(responseStream);
      }
      else {
        body = readErrorResponse(conn);
      }

      return new HttpResponse(statusCode, body, responseHeaders);
    }
    finally {
      if (responseStream != null) {
        try { responseStream.close(); } catch (IOException ignored) {}
      }
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Execute an HTTP PUT request with streaming data source (avoids loading full data into memory).
   *
   * @param url the URL to PUT
   * @param dataStream the input stream to read data from
   * @param contentLength the total content length (-1 for chunked)
   * @param contentType the Content-Type header
   * @param headers optional additional request headers
   * @return HttpResponse with status code and response body
   */
  public HttpResponse putStream(final String url, final InputStream dataStream,
                                 final long contentLength, final String contentType,
                                 final Map<String, String> headers) throws IOException {
    totalRequests.incrementAndGet();
    HttpURLConnection conn = null;
    InputStream responseStream = null;
    try {
      conn = openConnection(url, "PUT", headers);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", contentType);

      if (contentLength > 0) {
        conn.setFixedLengthStreamingMode(contentLength);
      }
      else {
        conn.setChunkedStreamingMode(chunkSize);
      }

      try (OutputStream os = conn.getOutputStream()) {
        byte[] buffer = new byte[chunkSize];
        int bytesRead;
        while ((bytesRead = dataStream.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
        os.flush();
      }

      int statusCode = conn.getResponseCode();
      Map<String, String> responseHeaders = extractResponseHeaders(conn);

      String body;
      if (statusCode >= 200 && statusCode < 300) {
        responseStream = conn.getInputStream();
        body = readStream(responseStream);
      }
      else {
        body = readErrorResponse(conn);
      }

      return new HttpResponse(statusCode, body, responseHeaders);
    }
    finally {
      if (responseStream != null) {
        try { responseStream.close(); } catch (IOException ignored) {}
      }
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Execute an HTTP HEAD request.
   */
  public HttpResponse head(final String url, final Map<String, String> headers) throws IOException {
    totalRequests.incrementAndGet();
    HttpURLConnection conn = null;
    try {
      conn = openConnection(url, "HEAD", headers);

      int statusCode = conn.getResponseCode();
      Map<String, String> responseHeaders = extractResponseHeaders(conn);

      return new HttpResponse(statusCode, "", responseHeaders);
    }
    finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Execute an HTTP DELETE request.
   */
  public HttpResponse delete(final String url, final Map<String, String> headers) throws IOException {
    totalRequests.incrementAndGet();
    HttpURLConnection conn = null;
    InputStream responseStream = null;
    try {
      conn = openConnection(url, "DELETE", headers);

      int statusCode = conn.getResponseCode();
      Map<String, String> responseHeaders = extractResponseHeaders(conn);

      if (statusCode >= 200 && statusCode < 300 && conn.getInputStream() != null) {
        responseStream = conn.getInputStream();
      }
      String body = responseStream != null ? readStream(responseStream) : "";
      return new HttpResponse(statusCode, body, responseHeaders);
    }
    finally {
      if (responseStream != null) {
        try { responseStream.close(); } catch (IOException ignored) {}
      }
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  // ==================== Internal helpers ====================

  private HttpURLConnection openConnection(final String url, final String method,
                                            final Map<String, String> headers) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(connectTimeoutMs);
    conn.setReadTimeout(readTimeoutMs);
    conn.setUseCaches(false);
    conn.setRequestProperty("Connection", "keep-alive");

    if (headers != null) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        conn.setRequestProperty(entry.getKey(), entry.getValue());
      }
    }

    return conn;
  }

  private Map<String, String> extractResponseHeaders(final HttpURLConnection conn) {
    Map<String, String> headers = new HashMap<>();
    for (Map.Entry<String, java.util.List<String>> entry : conn.getHeaderFields().entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
        headers.put(entry.getKey(), entry.getValue().get(0));
      }
    }
    return headers;
  }

  private String readStream(final InputStream is) throws IOException {
    if (is == null) return "";
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = is.read(buffer)) != -1) {
      bos.write(buffer, 0, bytesRead);
    }
    return bos.toString("UTF-8");
  }

  private String readErrorResponse(final HttpURLConnection conn) {
    try {
      if (conn.getErrorStream() != null) {
        return readStream(conn.getErrorStream());
      }
    }
    catch (IOException e) {
      // ignore
    }
    return "";
  }

  // ==================== Statistics ====================

  public int getTotalRequests() {
    return totalRequests.get();
  }

  public int getReusedConnections() {
    return reusedConnections.get();
  }

  public void resetStatistics() {
    totalRequests.set(0);
    reusedConnections.set(0);
  }

  // ==================== Response DTO ====================

  /**
   * HTTP response data transfer object.
   */
  public static class HttpResponse {
    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;

    public HttpResponse(final int statusCode, final String body, final Map<String, String> headers) {
      this.statusCode = statusCode;
      this.body = body;
      this.headers = headers != null ? headers : new HashMap<>();
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public Map<String, String> getHeaders() { return headers; }

    public String getHeader(final String name) {
      return headers.get(name);
    }

    public String getContentType() {
      return headers.get("Content-Type");
    }

    public String getDockerContentDigest() {
      return headers.get("Docker-Content-Digest");
    }

    public boolean isSuccess() {
      return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public String toString() {
      return "HttpResponse{statusCode=" + statusCode + ", bodyLength=" + (body != null ? body.length() : 0) + "}";
    }
  }
}
