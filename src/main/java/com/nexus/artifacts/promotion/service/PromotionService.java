package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.core.HttpHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.collect.ImmutableList;
import com.nexus.artifacts.promotion.model.FilePreviewResponse;
import com.nexus.artifacts.promotion.model.PromotionRequest;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.TargetRepositoryList;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;

/**
 * Service for artifact promotion between repositories.
 * Uses HTTP download/upload approach to avoid StorageTx transaction state issues.
 */
@Named
@Singleton
public class PromotionService {

  private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

  /** Connection/read timeout in milliseconds */
  private static final int TIMEOUT_MS = 300_000; // 5 minutes

  /** Buffer size for streaming */
  private static final int BUFFER_SIZE = 8192;

  /**
   * Trust-all-SSL manager for self-signed certificates.
   * Initialized once at class load time.
   */
  private static final TrustManager[] TRUST_ALL_CERTS = new TrustManager[]{
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] chain, String authType) { /* trust all */ }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { /* trust all */ }
      }
  };

  static {
    try {
      SSLContext sc = SSLContext.getInstance("TLS");
      sc.init(null, TRUST_ALL_CERTS, new SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
      HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
      log.info("SSL context initialized: trusting all certificates (supports self-signed HTTPS)");
    }
    catch (Exception e) {
      log.warn("Failed to initialize SSL trust manager: {}", e.getMessage(), e);
    }
  }

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;

  private final Map<String, PromotionTaskResult> taskResults = new ConcurrentHashMap<>();

  @Inject
  public PromotionService(final RepositoryManager repositoryManager,
                           final TaskExecutorService taskExecutor,
                           final TaskCacheManager cacheManager,
                           final PermissionChecker permissionChecker,
                           final SecurityHelper securityHelper)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
    this.securityHelper = securityHelper;
  }

  // ==================== Public API ====================

  /**
   * List target repositories where the current user has write permission.
   */
  public TargetRepositoryList listTargetRepositories(final String sourceRepository, final String format) {
    Repository sourceRepo = repositoryManager.get(sourceRepository);
    if (sourceRepo == null) {
      throw new IllegalArgumentException("Source repository not found: " + sourceRepository);
    }
    String actualFormat = sourceRepo.getFormat().getValue();

    TargetRepositoryList result = new TargetRepositoryList();
    result.setSourceRepository(sourceRepository);
    result.setFormat(actualFormat);

    List<TargetRepositoryList.TargetRepository> targets = new ArrayList<>();
    for (Repository repo : repositoryManager.browse()) {
      if (repo.getName().equals(sourceRepository)) continue;
      if (!actualFormat.equals(repo.getFormat().getValue())) continue;
      if (!permissionChecker.hasRepositoryWritePermission(repo.getName())) continue;
      targets.add(new TargetRepositoryList.TargetRepository(
          repo.getName(), repo.getFormat().getValue(), repo.getType().getValue(), repo.getUrl()));
    }

    result.setRepositories(targets);
    return result;
  }

  /**
   * Preview files that will be involved in a promotion.
   * Uses search API to find files matching the path prefix.
   */
  public FilePreviewResponse previewPromotion(final PromotionRequest request, final String nexusBaseUrl) {
    request.validate();
    permissionChecker.checkTargetWritePermission(request.getTargetRepository());

    FilePreviewResponse preview = new FilePreviewResponse();
    preview.setSourceRepository(request.getSourceRepository());
    preview.setTargetRepository(request.getTargetRepository());
    preview.setBasePath(request.getPath());

    List<FilePreviewResponse.FileEntry> files = new ArrayList<>();

    try {
      // Search for assets under the given path using REST API
      List<String> assetNames = searchAssets(request.getSourceRepository(), request.getPath(), nexusBaseUrl);

      if (assetNames.isEmpty()) {
        // If search returns nothing, treat the path itself as a single file/directory
        if (request.isDirectory()) {
          files.add(new FilePreviewResponse.FileEntry(
              request.getPath(), "directory", 0, false));
        } else {
          files.add(new FilePreviewResponse.FileEntry(
              request.getPath(), "file", 0, false));
        }
      } else {
        for (String name : assetNames) {
          String type = determineType(name);
          boolean existsInTarget = checkExistsViaHttp(request.getTargetRepository(), name, nexusBaseUrl);
          files.add(new FilePreviewResponse.FileEntry(name, type, 0, existsInTarget));
        }
      }
      preview.setTotalCount(files.size());
    }
    catch (Exception e) {
      log.error("Failed to preview promotion files: {}", e.getMessage(), e);
      files.add(new FilePreviewResponse.FileEntry(
          request.getPath(),
          request.isDirectory() ? "directory" : "file", 0, false));
      preview.setTotalCount(1);
    }

    preview.setFiles(files);
    return preview;
  }

  /**
   * Execute artifact promotion.
   * Returns task ID for tracking.
   * @param cookieHeader The raw Cookie header from the user's HTTP request for authentication
   * @param nexusBaseUrl The Nexus base URL (scheme://host:port) extracted from the incoming request
   */
  public String promote(final PromotionRequest request,
                         final String cookieHeader,
                         final String csrfToken,
                         final String nexusBaseUrl)
  {
    request.validate();
    permissionChecker.checkTargetWritePermission(request.getTargetRepository());

    String username = permissionChecker.getCurrentUsername();

    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;

    return taskExecutor.submitPromotionTask(() -> {
      if (threadState != null) {
        threadState.bind();
      }
      try {
        String taskId = null;
        PromotionTaskResult result = new PromotionTaskResult();
        result.setSourceRepository(request.getSourceRepository());
        result.setTargetRepository(request.getTargetRepository());
        result.setUsername(username);
        result.setStartTime(System.currentTimeMillis());

        try {
          taskId = cacheManager.createTaskCache("promo-" + System.currentTimeMillis()).getFileName().toString();
          result.setTaskId(taskId);

          List<PromotionTaskResult.FileItem> promotedItems = new ArrayList<>();

          if (request.isDirectory()) {
            promotedItems = promoteDirectoryViaHttp(
                request.getSourceRepository(), request.getTargetRepository(),
                request.getPath(), cookieHeader, csrfToken, result, nexusBaseUrl,
                request.getFiles());
          } else {
            promotedItems = promoteFileViaHttp(
                request.getSourceRepository(), request.getTargetRepository(),
                request.getPath(), cookieHeader, csrfToken, nexusBaseUrl);
          }

          result.setItems(promotedItems);
          result.setStatus(TaskStatus.COMPLETED);
          result.setEndTime(System.currentTimeMillis());

          log.info("Promotion task {} completed: {} items from {} to {}",
              taskId, promotedItems.size(), request.getSourceRepository(), request.getTargetRepository());
        }
        catch (Exception e) {
          log.error("Promotion task {} failed: {}", taskId, e.getMessage(), e);
          result.setStatus(TaskStatus.FAILED);
          result.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          result.setEndTime(System.currentTimeMillis());
        }

        if (taskId != null) {
          taskResults.put(taskId, result);
        }

        final String finalTaskId = taskId;
        return new TaskExecutorService.PromotionTaskCallback() {
          @Override public String getTaskId() { return finalTaskId; }
          @Override public TaskStatus getStatus() { return result.getStatus(); }
          @Override public String getErrorMessage() { return result.getErrorMessage(); }
        };
      }
      finally {
        if (threadState != null) {
          threadState.clear();
        }
      }
    }, String.format("Promote %s from %s to %s", request.getPath(),
        request.getSourceRepository(), request.getTargetRepository()));
  }

  /**
   * Get promotion task result.
   */
  public PromotionTaskResult getTaskResult(final String taskId) {
    return taskResults.get(taskId);
  }

  // ==================== HTTP-based Promotion Logic ====================

  /**
   * Promote all files in a directory via HTTP download/upload.
   * Updates task result with progress info for real-time status polling.
   */
  private List<PromotionTaskResult.FileItem> promoteDirectoryViaHttp(
      final String sourceRepo,
      final String targetRepo,
      final String directoryPath,
      final String cookieHeader,
      final String csrfToken,
      final PromotionTaskResult taskResult,
      final String nexusBaseUrl,
      final List<String> providedFiles) throws IOException
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();

    try {
      // Use provided file list from frontend preview, or fallback to search API
      List<String> assetNames = (providedFiles != null && !providedFiles.isEmpty())
          ? providedFiles
          : searchAssets(sourceRepo, directoryPath, nexusBaseUrl);

      if (assetNames.isEmpty()) {
        // Treat as single item if no sub-files found
        PromotionTaskResult.FileItem item = promoteSingleFileViaHttp(
            sourceRepo, targetRepo, directoryPath, cookieHeader, csrfToken, nexusBaseUrl);
        items.add(item);
        updateTaskProgress(taskResult, items);
        return items;
      }

      int total = assetNames.size();
      log.info("Directory promotion: {} files to promote from {}", total, directoryPath);

      for (int i = 0; i < total; i++) {
        String assetName = assetNames.get(i);
        log.info("[PROMO-PROGRESS] [{}/{}] Promoting: {}", i + 1, total,
            sourceRepo + "/" + assetName);

        try {
          PromotionTaskResult.FileItem item = promoteSingleFileViaHttp(
              sourceRepo, targetRepo, assetName, cookieHeader, csrfToken, nexusBaseUrl);
          items.add(item);
        }
        catch (Exception e) {
          log.error("Failed to promote {}: {}", assetName, e.getMessage());
          PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(
              sourceRepo + "/" + assetName, PromotionTaskResult.FileAction.UPDATED, determineType(assetName));
          failedItem.setStatus("failed");
          failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          items.add(failedItem);
        }

        // Update task result so frontend can poll progress
        updateTaskProgress(taskResult, items);
      }
    }
    catch (Exception e) {
      log.error("Failed to promote directory {}: {}", directoryPath, e.getMessage(), e);
      throw new RuntimeException("Directory promotion failed", e);
    }

    return items;
  }

  /**
   * Promote a single file via HTTP download/upload.
   */
  private List<PromotionTaskResult.FileItem> promoteFileViaHttp(
      final String sourceRepo,
      final String targetRepo,
      final String filePath,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();
    try {
      PromotionTaskResult.FileItem item = promoteSingleFileViaHttp(sourceRepo, targetRepo, filePath, cookieHeader, csrfToken, nexusBaseUrl);
      items.add(item);
    }
    catch (Exception e) {
      log.error("Failed to promote file {}: {}", filePath, e.getMessage(), e);
      throw new RuntimeException("File promotion failed: " + sanitizeErrorMessage(e.getMessage()), e);
    }
    return items;
  }

  /**
   * Core method: download a file from source repo and upload to target repo via HTTP.
   */
  private PromotionTaskResult.FileItem promoteSingleFileViaHttp(
      final String sourceRepo,
      final String targetRepo,
      final String filePath,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    String fullSourcePath = sourceRepo + "/" + filePath;
    log.debug("Promoting via HTTP: {} -> {}/{}", fullSourcePath, targetRepo, filePath);

    // Check if already exists in target
    boolean existsInTarget = checkExistsViaHttp(targetRepo, filePath, nexusBaseUrl);
    PromotionTaskResult.FileAction action = existsInTarget
        ? PromotionTaskResult.FileAction.UPDATED : PromotionTaskResult.FileAction.CREATED;

    // Download from source
    URL sourceUrl = new URL(nexusBaseUrl + "/repository/" + fullSourcePath);
    HttpURLConnection downloadConn = (HttpURLConnection) sourceUrl.openConnection();
    downloadConn.setRequestMethod("GET");
    downloadConn.setConnectTimeout(TIMEOUT_MS);
    downloadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(downloadConn, cookieHeader, csrfToken);

    int responseCode = downloadConn.getResponseCode();
    if (responseCode != 200) {
      String errorMsg = readErrorResponse(downloadConn);
      downloadConn.disconnect();
      throw new IOException("Download from " + fullSourcePath + " failed: HTTP " + responseCode + " - " + errorMsg);
    }

    // Upload to target
    URL targetUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + filePath);
    HttpURLConnection uploadConn = (HttpURLConnection) targetUrl.openConnection();
    uploadConn.setRequestMethod("PUT");
    uploadConn.setDoOutput(true);
    uploadConn.setConnectTimeout(TIMEOUT_MS);
    uploadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(uploadConn, cookieHeader, csrfToken);

    // Stream content from download to upload
    try (InputStream input = downloadConn.getInputStream();
         OutputStream output = uploadConn.getOutputStream()) {

      byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead;
      while ((bytesRead = input.read(buffer)) != -1) {
        output.write(buffer, 0, bytesRead);
      }
      output.flush();
    }
    finally {
      downloadConn.disconnect();
    }

    int uploadResponse = uploadConn.getResponseCode();
    String uploadMsg = "";
    if (uploadResponse >= 400) {
      uploadMsg = readErrorResponse(uploadConn);
    }
    uploadConn.disconnect();

    if (uploadResponse < 200 || uploadResponse > 299) {
      throw new IOException("Upload to " + targetRepo + "/" + filePath +
          " failed: HTTP " + uploadResponse + " - " + uploadMsg);
    }

    log.info("Successfully promoted: {} -> {}/{} ({})",
        fullSourcePath, targetRepo, filePath, action);
    return new PromotionTaskResult.FileItem(fullSourcePath, action, determineType(filePath));
  }

  // ==================== Helper Methods ====================

  /**
   * Search for assets under a path prefix using Nexus search API.
   */
  private List<String> searchAssets(final String repository, final String pathPrefix, final String nexusBaseUrl) throws IOException {
    List<String> results = new ArrayList<>();
    try {
      String encodedPrefix = pathPrefix.replace("%", "%25").replace("+", "%2B")
          .replace(" ", "%20").replace("/", "%2F");

      String searchUrlStr = nexusBaseUrl + "/service/rest/v1/search?repository=" +
          repository + "&name=" + encodedPrefix + "*";

      URL url = new URL(searchUrlStr);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(30_000);
      conn.setReadTimeout(60_000);
      conn.setRequestProperty("Accept", "application/json");

      int code = conn.getResponseCode();
      if (code == 200) {
        // Parse JSON response manually to avoid extra dependencies
        String json = readStream(conn.getInputStream());
        results = parseSearchItems(json, pathPrefix);
      }
      conn.disconnect();
    }
    catch (Exception e) {
      log.warn("Search API call failed for {}/{}, returning empty: {}", repository, pathPrefix, e.getMessage());
    }
    return results;
  }

  /**
   * Parse Nexus search API response to extract asset names.
   * Simple JSON parser that extracts "name" fields from items array.
   */
  private List<String> parseSearchItems(final String json, final String pathPrefix) {
    List<String> items = new ArrayList<>();
    if (json == null || json.isEmpty()) return items;

    // Simple parsing: find "items" array, then extract "name" from each item
    try {
      int itemsStart = json.indexOf("\"items\"");
      if (itemsStart < 0) return items;

      // Find the [ after "items"
      int arrayStart = json.indexOf('[', itemsStart);
      if (arrayStart < 0) return items;

      // Find matching ]
      int depth = 0;
      int pos = arrayStart;
      while (pos < json.length()) {
        char c = json.charAt(pos);
        if (c == '[') depth++;
        else if (c == ']') {
          depth--;
          if (depth == 0) break;
        }
        pos++;
      }
      String itemsArray = json.substring(arrayStart, pos + 1);

      // Extract each object and find "name"
      int objStart = 0;
      while ((objStart = itemsArray.indexOf('{', objStart)) >= 0) {
        int objEnd = findMatchingBrace(itemsArray, objStart);
        if (objEnd < 0) break;
        String obj = itemsArray.substring(objStart, objEnd + 1);

        String name = extractJsonString(obj, "name");
        if (name != null && !name.isEmpty()) {
          // Only include files that start with our path prefix
          if (name.startsWith(pathPrefix) && !name.equals(pathPrefix)) {
            items.add(name);
          }
        }
        objStart = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.warn("Failed to parse search response: {}", e.getMessage());
    }
    return items;
  }

  /**
   * Find matching closing brace.
   */
  private int findMatchingBrace(final String s, final int start) {
    int depth = 0;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  /**
   * Extract a string value by key from a JSON object string.
   */
  private String extractJsonString(final String json, final String key) {
    String searchKey = "\"" + key + "\"";
    int keyIdx = json.indexOf(searchKey);
    if (keyIdx < 0) return null;

    int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
    if (colonIdx < 0) return null;

    // Skip whitespace
    int valStart = colonIdx + 1;
    while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) {
      valStart++;
    }
    if (valStart >= json.length()) return null;

    if (json.charAt(valStart) == '"') {
      // String value
      StringBuilder sb = new StringBuilder();
      int i = valStart + 1;
      while (i < json.length()) {
        char c = json.charAt(i);
        if (c == '\\') {
          if (i + 1 < json.length()) {
            sb.append(json.charAt(i + 1));
            i += 2;
            continue;
          }
        }
        else if (c == '"') {
          break;
        }
        sb.append(c);
        i++;
      }
      return sb.toString();
    }
    return null;
  }

  /**
   * Check if an asset exists in target repository via HEAD request.
   */
  private boolean checkExistsViaHttp(final String targetRepo, final String assetName, final String nexusBaseUrl) {
    try {
      URL url = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + assetName);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("HEAD");
      conn.setConnectTimeout(10_000);
      conn.setReadTimeout(10_000);
      int code = conn.getResponseCode();
      conn.disconnect();
      return code == 200;
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Set authentication headers on HTTP connection.
   * Uses the user's session cookie and CSRF token from the original request.
   */
  private void setAuthHeaders(final HttpURLConnection conn, final String cookieHeader, final String csrfToken) {
    if (cookieHeader != null && !cookieHeader.isEmpty()) {
      conn.setRequestProperty(HttpHeaders.COOKIE, cookieHeader);
    }
    if (csrfToken != null && !csrfToken.isEmpty()) {
      conn.setRequestProperty("NX-ANTI-CSRF-TOKEN", csrfToken);
    }
    conn.setRequestProperty("X-Nexus-UI", "true");
    conn.setRequestProperty("Accept", "*/*");
  }

  /**
   * Read error response body from connection.
   */
  private String readErrorResponse(final HttpURLConnection conn) throws IOException {
    InputStream errStream = conn.getErrorStream();
    if (errStream != null) {
      return readStream(errStream);
    }
    return "";
  }

  /**
   * Read entire stream into a string.
   */
  private String readStream(final InputStream input) throws IOException {
    StringBuilder sb = new StringBuilder();
    byte[] buffer = new byte[BUFFER_SIZE];
    int bytesRead;
    while ((bytesRead = input.read(buffer)) != -1) {
      sb.append(new String(buffer, 0, bytesRead, "UTF-8"));
    }
    return sb.toString();
  }

  /**
   * Update task result with current progress (for polling).
   */
  private void updateTaskProgress(final PromotionTaskResult taskResult,
                                   final List<PromotionTaskResult.FileItem> items) {
    if (taskResult != null) {
      taskResult.setItems(new ArrayList<>(items)); // Copy for thread safety
    }
  }

  private String determineType(final String path) {
    if (path == null) return "file";
    if (path.contains("/blobs/") || path.contains("/manifests/") || path.endsWith(".manifest")) {
      return "image";
    }
    if (path.endsWith("/")) {
      return "directory";
    }
    return "file";
  }

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }
}
