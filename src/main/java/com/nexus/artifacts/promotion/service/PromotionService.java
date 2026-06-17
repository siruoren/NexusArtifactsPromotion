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
import java.util.UUID;
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
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;

import com.google.common.hash.HashCode;

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

  /** Maximum time (ms) to keep completed task results before cleanup (30 minutes) */
  private static final long TASK_RESULT_TTL_MS = 30 * 60 * 1000L;

  /** Connection/read timeout in milliseconds */
  private static final int TIMEOUT_MS = 300_000; // 5 minutes

  /** Buffer size for streaming */
  private static final int BUFFER_SIZE = 8192;

  /** Maximum retry attempts for individual file operations */
  private static final int FILE_RETRY_ATTEMPTS = 3;

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
      // Skip remote/proxy repositories - cannot upload to them via promotion
      String repoType = repo.getType().getValue();
      if ("proxy".equals(repoType)) continue;
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
        // No files found via search - for directory promotion, return empty list
        // so the UI can show "no files found" rather than promoting the directory itself
        if (!request.isDirectory()) {
          files.add(new FilePreviewResponse.FileEntry(
              request.getPath(), "file", 0));
        }
        // For directory: leave files empty, will show error message to user
      } else {
        for (String name : assetNames) {
          String type = determineType(name);
          files.add(new FilePreviewResponse.FileEntry(name, type, 0));
        }
      }
      preview.setTotalCount(files.size());
    }
    catch (Exception e) {
      log.error("Failed to preview promotion files: {}", e.getMessage(), e);
      // Only add single entry for file promotion, not for directory
      if (!request.isDirectory()) {
        files.add(new FilePreviewResponse.FileEntry(
            request.getPath(), "file", 0));
        preview.setTotalCount(1);
      }
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

    // Generate taskId upfront so callable and TaskExecutorService use the same ID
    final String taskId = "promo-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;

    return taskExecutor.submitPromotionTask(() -> {
      if (threadState != null) {
        threadState.bind();
      }
      try {
        PromotionTaskResult result = new PromotionTaskResult();
        result.setSourceRepository(request.getSourceRepository());
        result.setTargetRepository(request.getTargetRepository());
        result.setUsername(username);
        result.setStartTime(System.currentTimeMillis());
        result.setTaskId(taskId);
        result.setStatus(TaskStatus.RUNNING.getValue());

        // Put initial result so frontend can poll immediately
        taskResults.put(taskId, copyResult(result));

        List<PromotionTaskResult.FileItem> promotedItems = new ArrayList<>();

        try {
          if (request.isDirectory()) {
            promotedItems = promoteDirectoryViaHttp(
                request.getSourceRepository(), request.getTargetRepository(),
                request.getPath(), cookieHeader, csrfToken, result, nexusBaseUrl,
                request.getFiles(), request.getFormat());
          } else {
            promotedItems = promoteFileViaHttp(
                request.getSourceRepository(), request.getTargetRepository(),
                request.getPath(), cookieHeader, csrfToken, nexusBaseUrl);
          }

          result.setItems(promotedItems);
          result.setStatus(TaskStatus.COMPLETED.getValue());
          result.setEndTime(System.currentTimeMillis());

          long skippedCount = promotedItems.stream().filter(f -> "skipped".equals(f.getStatus())).count();
          long promotedCount = promotedItems.size() - skippedCount;
          log.info("Promotion task {} completed: {} items promoted, {} skipped from {} to {}",
              taskId, promotedCount, skippedCount, request.getSourceRepository(), request.getTargetRepository());
        }
        catch (Exception e) {
          log.error("Promotion task {} failed: {}", taskId, e.getMessage(), e);
          result.setStatus(TaskStatus.FAILED.getValue());
          result.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          result.setEndTime(System.currentTimeMillis());
        }

        taskResults.put(taskId, copyResult(result));

        return new TaskExecutorService.PromotionTaskCallback() {
          @Override public String getTaskId() { return taskId; }
          @Override public TaskStatus getStatus() { return TaskStatus.fromValue(result.getStatus()); }
          @Override public String getErrorMessage() { return result.getErrorMessage(); }
        };
      }
      finally {
        if (threadState != null) {
          threadState.clear();
        }
      }
    }, String.format("Promote %s from %s to %s", request.getPath(),
        request.getSourceRepository(), request.getTargetRepository()), taskId);
  }

  /**
   * Get promotion task result.
   * Also triggers cleanup of expired task results.
   */
  public PromotionTaskResult getTaskResult(final String taskId) {
    cleanupExpiredTaskResults();
    PromotionTaskResult result = taskResults.get(taskId);
    if (result != null) {
      // Clean up executor handle for terminal tasks
      String statusStr = result.getStatus();
      if ("COMPLETED".equals(statusStr) || "FAILED".equals(statusStr)) {
        taskExecutor.cleanupPromotionTaskHandle(taskId);
      }
    }
    return result;
  }

  /**
   * Get task status from TaskExecutorService (for running tasks not yet in taskResults).
   */
  public TaskStatus getTaskExecutorStatus(final String taskId) {
    return taskExecutor.getPromotionTaskStatus(taskId);
  }

  /**
   * Clean up task results that have been completed for longer than TASK_RESULT_TTL_MS.
   * This prevents memory leaks from accumulated completed task data.
   */
  private void cleanupExpiredTaskResults() {
    long now = System.currentTimeMillis();
    List<String> expiredTaskIds = new ArrayList<>();

    for (Map.Entry<String, PromotionTaskResult> entry : taskResults.entrySet()) {
      PromotionTaskResult result = entry.getValue();
      String statusStr = result.getStatus();
      if (("COMPLETED".equals(statusStr) || "FAILED".equals(statusStr))
          && result.getEndTime() > 0
          && (now - result.getEndTime()) > TASK_RESULT_TTL_MS) {
        expiredTaskIds.add(entry.getKey());
      }
    }

    for (String taskId : expiredTaskIds) {
      taskResults.remove(taskId);
      taskExecutor.cleanupPromotionTaskHandle(taskId);
      log.debug("Expired promotion task result cleaned up: {}", taskId);
    }

    if (!expiredTaskIds.isEmpty()) {
      log.info("Cleaned up {} expired promotion task results", expiredTaskIds.size());
    }
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
      final List<String> providedFiles,
      final String format) throws IOException
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();

    // Log what we received from frontend
    log.info("Directory promotion called: source={}, target={}, path={}, providedFiles={}",
        sourceRepo, targetRepo, directoryPath,
        (providedFiles != null ? providedFiles.size() + " items" : "null"));
    if (providedFiles != null) {
      for (int i = 0; i < providedFiles.size(); i++) {
        log.info("  providedFile[{}]: {}", i, providedFiles.get(i));
      }
    }

    try {
      List<String> assetNames;

      if (providedFiles != null && !providedFiles.isEmpty()) {
        // Use files from frontend preview - filter out any non-file entries
        assetNames = new ArrayList<>();
        for (String f : providedFiles) {
          if (f == null || f.isEmpty()) continue;
          if (f.equals(directoryPath)) {
            log.warn("Frontend sent directory path itself as file, skipping: {}", f);
            continue;
          }
          if (f.endsWith("/")) {
            log.warn("Frontend sent directory entry, skipping: {}", f);
            continue;
          }
          assetNames.add(f);
        }
        log.info("After filtering providedFiles: {} valid files", assetNames.size());
      } else {
        // No files from frontend - try search API
        log.warn("No files from frontend, falling back to search API for {}/{}",
            sourceRepo, directoryPath);
        assetNames = searchAssets(sourceRepo, directoryPath, nexusBaseUrl);
        // Filter search results too
        List<String> filtered = new ArrayList<>();
        for (String name : assetNames) {
          if (!name.equals(directoryPath) && !name.endsWith("/")) {
            filtered.add(name);
          }
        }
        assetNames = filtered;
      }

      if (assetNames.isEmpty()) {
        throw new IOException("No files found under directory '" + directoryPath +
            "' in repository " + sourceRepo + ". Cannot promote an empty directory.");
      }

      // For Docker format: sort to ensure blobs are promoted before manifests
      // Docker registries require all referenced blobs to exist before a manifest can be pushed
      if ("docker".equalsIgnoreCase(format)) {
        assetNames.sort((a, b) -> {
          boolean aBlob = a.contains("/blobs/");
          boolean bBlob = b.contains("/blobs/");
          boolean aManifest = a.contains("/manifests/");
          boolean bManifest = b.contains("/manifests/");
          // Blobs first, then manifests, then other
          int aOrder = aBlob ? 0 : (aManifest ? 2 : 1);
          int bOrder = bBlob ? 0 : (bManifest ? 2 : 1);
          return Integer.compare(aOrder, bOrder);
        });
        log.info("Docker promotion: sorted files to push blobs before manifests");
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
              assetName, determineType(assetName));
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
   * Implements incremental sync: compares MD5 checksums of source and target assets;
   * if they match, the file is skipped (status="skipped") without re-uploading.
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

    // Incremental check: compare MD5 of source and target assets via internal Java API
    String sourceMd5 = getAssetMd5(sourceRepo, filePath);
    String targetMd5 = getAssetMd5(targetRepo, filePath);

    if (sourceMd5 != null && targetMd5 != null && sourceMd5.equalsIgnoreCase(targetMd5)) {
      log.info("Skipping promotion (MD5 match): {} -> {}/{}, MD5={}",
          fullSourcePath, targetRepo, filePath, sourceMd5);
      PromotionTaskResult.FileItem skippedItem = new PromotionTaskResult.FileItem(filePath, determineType(filePath));
      skippedItem.setStatus("skipped");
      skippedItem.setSourceMd5(sourceMd5);
      skippedItem.setTargetMd5(targetMd5);
      return skippedItem;
    }

    log.info("MD5 differs or missing, promoting: sourceMd5={}, targetMd5={}, path={}",
        sourceMd5, targetMd5, filePath);

    // Use retry for the actual download+upload operation
    try {
      PromotionTaskResult.FileItem result = RetryableOperation.execute(
          "promote " + filePath,
          () -> doPromoteFileTransfer(sourceRepo, targetRepo, filePath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5),
          FILE_RETRY_ATTEMPTS);
      return result;
    }
    catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Promotion failed after retries: " + e.getMessage(), e);
    }
  }

  /**
   * Perform the actual file transfer (download + upload) for promotion.
   * Separated from promoteSingleFileViaHttp to enable retry.
   * Uses Transfer-Encoding: chunked for large file streaming.
   */
  private PromotionTaskResult.FileItem doPromoteFileTransfer(
      final String sourceRepo,
      final String targetRepo,
      final String filePath,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5,
      final String targetMd5) throws IOException
  {
    String fullSourcePath = sourceRepo + "/" + filePath;

    // Special handling for maven-metadata.xml: merge instead of overwrite
    if (MavenMetadataMerger.isMavenMetadata(filePath)) {
      return promoteMavenMetadata(sourceRepo, targetRepo, filePath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5);
    }

    // Special handling for Docker format: use Docker v2 API for push
    // Docker repositories don't accept direct PUT uploads - they require the Docker v2 API
    if (isDockerAssetPath(filePath)) {
      return promoteDockerAsset(sourceRepo, targetRepo, filePath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5);
    }

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

    // Upload to target — use chunked streaming for large files
    URL targetUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + filePath);
    HttpURLConnection uploadConn = (HttpURLConnection) targetUrl.openConnection();
    uploadConn.setRequestMethod("PUT");
    uploadConn.setDoOutput(true);
    uploadConn.setConnectTimeout(TIMEOUT_MS);
    uploadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(uploadConn, cookieHeader, csrfToken);

    // Enable chunked streaming with 64KB chunks — avoids buffering entire file in memory
    // This is critical for large Docker blobs (can be GBs)
    uploadConn.setChunkedStreamingMode(64 * 1024);

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

    log.info("Successfully promoted: {} -> {}/{}",
        fullSourcePath, targetRepo, filePath);
    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(filePath, determineType(filePath));
    item.setSourceMd5(sourceMd5);
    item.setTargetMd5(targetMd5);
    return item;
  }

  /**
   * Promote a maven-metadata.xml file with smart merge.
   * If the target already has a maven-metadata.xml, the two files are merged
   * (union of version entries, highest latest/release, most recent lastUpdated).
   * If the target doesn't have one, the source is promoted as-is.
   * Checksum files (maven-metadata.xml.md5/.sha1/.sha256/.sha512) are regenerated.
   */
  private PromotionTaskResult.FileItem promoteMavenMetadata(
      final String sourceRepo, final String targetRepo,
      final String filePath,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5, final String targetMd5) throws IOException
  {
    String fullSourcePath = sourceRepo + "/" + filePath;
    log.info("Promoting maven-metadata.xml with merge: {}", fullSourcePath);

    // Download source metadata
    URL sourceUrl = new URL(nexusBaseUrl + "/repository/" + fullSourcePath);
    HttpURLConnection sourceConn = (HttpURLConnection) sourceUrl.openConnection();
    sourceConn.setRequestMethod("GET");
    sourceConn.setConnectTimeout(TIMEOUT_MS);
    sourceConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(sourceConn, cookieHeader, csrfToken);

    int sourceResponse = sourceConn.getResponseCode();
    if (sourceResponse != 200) {
      String error = readErrorResponse(sourceConn);
      sourceConn.disconnect();
      throw new IOException("Download maven-metadata.xml failed: HTTP " + sourceResponse + " - " + error);
    }
    String sourceContent = readStream(sourceConn.getInputStream());
    sourceConn.disconnect();

    String mergedContent = sourceContent;

    // If target already has the metadata, download and merge
    if (targetMd5 != null) {
      try {
        URL targetUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + filePath);
        HttpURLConnection targetConn = (HttpURLConnection) targetUrl.openConnection();
        targetConn.setRequestMethod("GET");
        targetConn.setConnectTimeout(TIMEOUT_MS);
        targetConn.setReadTimeout(TIMEOUT_MS);
        setAuthHeaders(targetConn, cookieHeader, csrfToken);

        if (targetConn.getResponseCode() == 200) {
          String targetContent = readStream(targetConn.getInputStream());
          mergedContent = MavenMetadataMerger.merge(sourceContent, targetContent);
          log.info("Merged maven-metadata.xml for {}/{}", targetRepo, filePath);
        }
        targetConn.disconnect();
      }
      catch (Exception e) {
        log.warn("Failed to merge maven-metadata.xml, using source content: {}", e.getMessage());
      }
    }

    // Upload merged content to target
    URL uploadUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + filePath);
    HttpURLConnection uploadConn = (HttpURLConnection) uploadUrl.openConnection();
    uploadConn.setRequestMethod("PUT");
    uploadConn.setDoOutput(true);
    uploadConn.setConnectTimeout(TIMEOUT_MS);
    uploadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(uploadConn, cookieHeader, csrfToken);

    try (OutputStream output = uploadConn.getOutputStream()) {
      output.write(mergedContent.getBytes("UTF-8"));
      output.flush();
    }

    int uploadResponse = uploadConn.getResponseCode();
    if (uploadResponse >= 400) {
      String error = readErrorResponse(uploadConn);
      uploadConn.disconnect();
      throw new IOException("Upload merged maven-metadata.xml failed: HTTP " + uploadResponse + " - " + error);
    }
    uploadConn.disconnect();

    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(filePath, "file");
    item.setSourceMd5(sourceMd5);
    item.setTargetMd5(targetMd5);
    return item;
  }

  // ========================================================================
  // MD5 checksum retrieval via Nexus internal Java API
  // ========================================================================

  /**
   * Get the MD5 checksum of an asset using Nexus internal StorageTx API.
   * This is the most reliable way to get checksums — no HTTP roundtrip,
   * no authentication issues, reads directly from the blob store.
   * Returns null if the asset is not found or MD5 is not available.
   */
  private String getAssetMd5(final String repoName, final String assetPath) {
    try {
      Repository repo = repositoryManager.get(repoName);
      if (repo == null) {
        log.debug("Repository not found for MD5 lookup: {}", repoName);
        return null;
      }

      boolean isDocker = "docker".equals(repo.getFormat().getValue());

      StorageTx tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      try {
        Bucket bucket = tx.findBucket(repo);
        Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);

        // Fallback: try with leading slash
        if (asset == null && !assetPath.startsWith("/")) {
          asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
        }

        if (asset == null) {
          log.debug("Asset not found for MD5 lookup: {}/{}", repoName, assetPath);
          return null;
        }

        // Method 1: Use Asset.getChecksum(HashAlgorithm.MD5) — Nexus 3.7+
        try {
          HashCode md5Hash = asset.getChecksum(HashAlgorithm.MD5);
          if (md5Hash != null) {
            String md5 = md5Hash.toString();
            if (!md5.isEmpty()) {
              log.debug("Found MD5 via Asset.getChecksum() for {}/{}: {}", repoName, assetPath, md5);
              return md5;
            }
          }
        }
        catch (Exception e) {
          log.debug("Asset.getChecksum(MD5) not available or failed for {}/{}: {}", repoName, assetPath, e.getMessage());
        }

        // Method 2: Try blob headers for Content-Hash-MD5
        try {
          if (asset.requireBlobRef() != null) {
            Blob blob = tx.requireBlob(asset.requireBlobRef());
            if (blob != null) {
              Map<String, String> headers = blob.getHeaders();
              String md5 = headers.get("Content-Hash-MD5");
              if (md5 == null || md5.isEmpty()) {
                // Try alternate header key format used in some Nexus versions
                md5 = headers.get("content-hash-md5");
              }
              if (md5 != null && !md5.isEmpty()) {
                log.debug("Found MD5 via blob headers for {}/{}: {}", repoName, assetPath, md5);
                return md5;
              }
            }
          }
        }
        catch (Exception e) {
          log.debug("Blob header MD5 lookup failed for {}/{}: {}", repoName, assetPath, e.getMessage());
        }

        // Method 3: For Docker format, fallback to SHA256
        if (isDocker) {
          try {
            HashCode sha256Hash = asset.getChecksum(HashAlgorithm.SHA256);
            if (sha256Hash != null) {
              String sha256 = sha256Hash.toString();
              if (!sha256.isEmpty()) {
                String result = sha256.startsWith("sha256:") ? sha256 : "sha256:" + sha256;
                log.debug("Found SHA256 via Asset.getChecksum() for Docker {}/{}: {}", repoName, assetPath, result);
                return result;
              }
            }
          }
          catch (Exception e) {
            log.debug("Asset.getChecksum(SHA256) failed for Docker {}/{}: {}", repoName, assetPath, e.getMessage());
          }

          // Try blob headers for Content-Hash-SHA256
          try {
            if (asset.requireBlobRef() != null) {
              Blob blob = tx.requireBlob(asset.requireBlobRef());
              if (blob != null) {
                Map<String, String> headers = blob.getHeaders();
                String sha256 = headers.get("Content-Hash-SHA256");
                if (sha256 == null || sha256.isEmpty()) {
                  sha256 = headers.get("content-hash-sha256");
                }
                if (sha256 != null && !sha256.isEmpty()) {
                  String result = sha256.startsWith("sha256:") ? sha256 : "sha256:" + sha256;
                  log.debug("Found SHA256 via blob headers for Docker {}/{}: {}", repoName, assetPath, result);
                  return result;
                }
              }
            }
          }
          catch (Exception e) {
            log.debug("Blob header SHA256 lookup failed for Docker {}/{}: {}", repoName, assetPath, e.getMessage());
          }
        }
      }
      finally {
        tx.close();
      }
    }
    catch (Exception e) {
      log.debug("Failed to get MD5 via internal API for {}/{}: {}", repoName, assetPath, e.getMessage());
    }
    return null;
  }

  // ==================== Helper Methods ====================

  /**
   * Search for assets under a path prefix using Nexus search API.
   * Tries multiple approaches: search API → components API fallback.
   */
  private List<String> searchAssets(final String repository, final String pathPrefix, final String nexusBaseUrl) throws IOException {
    List<String> results = new ArrayList<>();

    // Normalize path: strip trailing slash
    String normalizedPrefix = (pathPrefix != null && pathPrefix.endsWith("/"))
        ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;

    // Approach 0: Internal Java API (most reliable, no auth issues)
    results = searchAssetsViaInternalApi(repository, normalizedPrefix);
    if (!results.isEmpty()) {
      log.debug("Internal API found {} items for {}/{}", results.size(), repository, normalizedPrefix);
      return results;
    }

    // Approach 1: Search API with wildcard
    results = trySearchApi(repository, normalizedPrefix, nexusBaseUrl);

    // Approach 2: Components API fallback if search returned nothing
    if (results.isEmpty() && normalizedPrefix != null && !normalizedPrefix.isEmpty()) {
      log.debug("Search API returned empty for {}/{}, trying components API", repository, normalizedPrefix);
      results = tryComponentsApi(repository, normalizedPrefix, nexusBaseUrl);
    }

    return results;
  }

  /**
   * Search assets using Nexus internal StorageTx API.
   * This is the most reliable approach as it bypasses HTTP authentication entirely.
   * Works for all repository types including proxy repositories.
   */
  private List<String> searchAssetsViaInternalApi(final String repository, final String pathPrefix) {
    List<String> results = new ArrayList<>();
    try {
      Repository repo = repositoryManager.get(repository);
      if (repo == null) {
        log.debug("Repository not found for internal search: {}", repository);
        return results;
      }

      StorageTx tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      try {
        Bucket bucket = tx.findBucket(repo);
        if (bucket == null) {
          log.debug("Bucket not found for repository: {}", repository);
          return results;
        }

        // Browse all assets and filter by path prefix
        Iterable<Asset> assets = tx.browseAssets(bucket);
        String normalizedPrefix = pathPrefix;
        if (normalizedPrefix != null && normalizedPrefix.startsWith("/")) {
          normalizedPrefix = normalizedPrefix.substring(1);
        }

        for (Asset asset : assets) {
          String name = asset.name();
          if (name == null) continue;

          // Normalize: strip leading slash
          String normalizedName = name.startsWith("/") ? name.substring(1) : name;

          if (normalizedPrefix == null || normalizedPrefix.isEmpty()) {
            // No prefix filter - include all non-directory assets
            if (!normalizedName.endsWith("/")) {
              results.add(normalizedName);
            }
          }
          else {
            // Include assets under the path prefix
            if (normalizedName.startsWith(normalizedPrefix + "/") && !normalizedName.endsWith("/")) {
              results.add(normalizedName);
            }
          }
        }

        log.debug("Internal API found {} assets in repo {} under prefix '{}'",
            results.size(), repository, normalizedPrefix);
      }
      finally {
        tx.close();
      }
    }
    catch (Exception e) {
      log.debug("Internal API search failed for {}/{}: {}", repository, pathPrefix, e.getMessage());
    }
    return results;
  }

  /**
   * Try Nexus Search API to find assets.
   */
  private List<String> trySearchApi(final String repository, final String pathPrefix,
                                     final String nexusBaseUrl) throws IOException {
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
      if (code == 403 || code == 401) {
        log.debug("Search API returned {} for {}/{}, may need authentication", code, repository, pathPrefix);
        conn.disconnect();
        return results;
      }
      if (code == 200) {
        String json = readStream(conn.getInputStream());
        results = parseSearchItems(json, pathPrefix);
        log.debug("Search API found {} items for {}/{}", results.size(), repository, pathPrefix);
      }
      conn.disconnect();
    }
    catch (Exception e) {
      log.warn("Search API call failed for {}/{}, returning empty: {}", repository, pathPrefix, e.getMessage());
    }
    return results;
  }

  /**
   * Fallback: Use components API to list all components and filter by path prefix.
   */
  private List<String> tryComponentsApi(final String repository, final String pathPrefix,
                                         final String nexusBaseUrl) throws IOException {
    List<String> results = new ArrayList<>();
    try {
      String urlStr = nexusBaseUrl + "/service/rest/v1/components?repository=" + repository;
      URL url = new URL(urlStr);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(30_000);
      conn.setReadTimeout(120_000);
      conn.setRequestProperty("Accept", "application/json");

      int code = conn.getResponseCode();
      if (code == 200) {
        String json = readStream(conn.getInputStream());
        results = parseComponentAssets(json, pathPrefix);
        log.debug("Components API found {} items for {}/{}", results.size(), repository, pathPrefix);
      }
      conn.disconnect();
    }
    catch (Exception e) {
      log.warn("Components API call failed for {}/{}: {}", repository, pathPrefix, e.getMessage());
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
   * Parse Nexus Components API response to extract asset paths.
   * Response format: { "items": [ { "assets": [ { "path": "...", ... } ], ... }, ... ] }
   */
  private List<String> parseComponentAssets(final String json, final String pathPrefix) {
    List<String> results = new ArrayList<>();
    if (json == null || json.isEmpty()) return results;

    try {
      int itemsStart = json.indexOf("\"items\"");
      if (itemsStart < 0) return results;

      int arrayStart = json.indexOf('[', itemsStart);
      if (arrayStart < 0) return results;

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

      // Extract each component object
      int objStart = 0;
      while ((objStart = itemsArray.indexOf('{', objStart)) >= 0) {
        int objEnd = findMatchingBrace(itemsArray, objStart);
        if (objEnd < 0) break;
        String componentObj = itemsArray.substring(objStart, objEnd + 1);

        // Find "assets" array inside this component
        int assetsIdx = componentObj.indexOf("\"assets\"");
        if (assetsIdx >= 0) {
          int assetsArrStart = componentObj.indexOf('[', assetsIdx);
          if (assetsArrStart >= 0) {
            // Find matching ] for assets array
            int aDepth = 0;
            int aPos = assetsArrStart;
            while (aPos < componentObj.length()) {
              char c = componentObj.charAt(aPos);
              if (c == '[') aDepth++;
              else if (c == ']') {
                aDepth--;
                if (aDepth == 0) break;
              }
              aPos++;
            }
            String assetsArr = componentObj.substring(assetsArrStart, aPos + 1);

            // Extract each asset object and get "path"
            int aObjStart = 0;
            while ((aObjStart = assetsArr.indexOf('{', aObjStart)) >= 0) {
              int aObjEnd = findMatchingBrace(assetsArr, aObjStart);
              if (aObjEnd < 0) break;
              String assetObj = assetsArr.substring(aObjStart, aObjEnd + 1);

              String assetPath = extractJsonString(assetObj, "path");
              if (assetPath != null && !assetPath.isEmpty()
                  && assetPath.startsWith(pathPrefix)
                  && !assetPath.equals(pathPrefix)) {
                results.add(assetPath);
              }
              aObjStart = aObjEnd + 1;
            }
          }
        }

        objStart = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.warn("Failed to parse components response: {}", e.getMessage());
    }
    return results;
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
   * Create a deep copy of a PromotionTaskResult for thread-safe publishing.
   * Each put into taskResults must use a copy so that the task thread's
   * subsequent mutations don't affect the snapshot seen by polling threads.
   */
  private PromotionTaskResult copyResult(PromotionTaskResult src) {
    if (src == null) return null;
    PromotionTaskResult copy = new PromotionTaskResult();
    copy.setTaskId(src.getTaskId());
    copy.setSourceRepository(src.getSourceRepository());
    copy.setTargetRepository(src.getTargetRepository());
    copy.setStatus(src.getStatus());
    copy.setUsername(src.getUsername());
    copy.setStartTime(src.getStartTime());
    copy.setEndTime(src.getEndTime());
    copy.setErrorMessage(src.getErrorMessage());
    if (src.getItems() != null) {
      List<PromotionTaskResult.FileItem> itemsCopy = new ArrayList<>();
      for (PromotionTaskResult.FileItem item : src.getItems()) {
        PromotionTaskResult.FileItem itemCopy = new PromotionTaskResult.FileItem();
        itemCopy.setPath(item.getPath());
        itemCopy.setType(item.getType());
        itemCopy.setStatus(item.getStatus());
        itemCopy.setErrorMessage(item.getErrorMessage());
        itemCopy.setSourceMd5(item.getSourceMd5());
        itemCopy.setTargetMd5(item.getTargetMd5());
        itemsCopy.add(itemCopy);
      }
      copy.setItems(itemsCopy);
    }
    return copy;
  }

  /**
   * Update task result with current progress (for polling).
   */
  private void updateTaskProgress(final PromotionTaskResult taskResult,
                                   final List<PromotionTaskResult.FileItem> items) {
    if (taskResult != null) {
      taskResult.setItems(new ArrayList<>(items));
      String tid = taskResult.getTaskId();
      if (tid != null) {
        taskResults.put(tid, copyResult(taskResult));
      }
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

  /**
   * Check if the asset path is a Docker v2 format path.
   * Docker paths start with "v2/" and contain /manifests/ or /blobs/ segments.
   */
  private boolean isDockerAssetPath(final String path) {
    if (path == null) return false;
    return path.startsWith("v2/") && (path.contains("/manifests/") || path.contains("/blobs/"));
  }

  /**
   * Promote a Docker asset using Docker v2 API.
   * Docker repositories don't accept direct PUT uploads - they require the Docker v2 API:
   * - Manifests: PUT /v2/<name>/manifests/<tag> with proper Content-Type
   * - Blobs: POST /v2/<name>/blobs/uploads/ then PUT with digest
   */
  private PromotionTaskResult.FileItem promoteDockerAsset(
      final String sourceRepo,
      final String targetRepo,
      final String filePath,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5,
      final String targetMd5) throws IOException
  {
    // Parse Docker path: v2/<image>/manifests/<tag> or v2/<image>/blobs/<digest>
    String pathWithoutV2 = filePath.substring(3); // remove "v2/"

    int manifestIdx = pathWithoutV2.indexOf("/manifests/");
    int blobsIdx = pathWithoutV2.indexOf("/blobs/");

    if (manifestIdx >= 0) {
      return promoteDockerManifest(sourceRepo, targetRepo, pathWithoutV2, manifestIdx,
          cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5);
    }
    else if (blobsIdx >= 0) {
      return promoteDockerBlob(sourceRepo, targetRepo, filePath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5);
    }
    else {
      throw new IOException("Unknown Docker asset path format: " + filePath);
    }
  }

  /**
   * Promote a Docker manifest using Docker v2 API.
   * Downloads the manifest from source, parses referenced blobs and pushes them first,
   * then pushes the manifest to target via PUT /v2/<name>/manifests/<tag>.
   */
  private PromotionTaskResult.FileItem promoteDockerManifest(
      final String sourceRepo,
      final String targetRepo,
      final String pathWithoutV2,
      final int manifestIdx,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5,
      final String targetMd5) throws IOException
  {
    String imageName = pathWithoutV2.substring(0, manifestIdx);
    String tag = pathWithoutV2.substring(manifestIdx + "/manifests/".length());
    String filePath = "v2/" + pathWithoutV2;

    // Step 1: Download manifest from source with proper Accept headers
    URL sourceUrl = new URL(nexusBaseUrl + "/repository/" + sourceRepo + "/" + filePath);
    HttpURLConnection downloadConn = (HttpURLConnection) sourceUrl.openConnection();
    downloadConn.setRequestMethod("GET");
    downloadConn.setConnectTimeout(TIMEOUT_MS);
    downloadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(downloadConn, cookieHeader, csrfToken);
    downloadConn.setRequestProperty("Accept",
        "application/vnd.docker.distribution.manifest.v2+json," +
        "application/vnd.docker.distribution.manifest.list.v2+json," +
        "application/vnd.oci.image.manifest.v1+json," +
        "application/json");

    int downloadCode = downloadConn.getResponseCode();
    if (downloadCode != 200) {
      String errorMsg = readErrorResponse(downloadConn);
      downloadConn.disconnect();
      throw new IOException("Download manifest from " + sourceRepo + "/" + filePath +
          " failed: HTTP " + downloadCode + " - " + errorMsg);
    }

    // Get the Content-Type of the manifest (critical for Docker push)
    String contentType = downloadConn.getContentType();
    if (contentType == null || contentType.isEmpty()) {
      contentType = "application/vnd.docker.distribution.manifest.v2+json";
    }

    // Read manifest content
    byte[] manifestBytes;
    try (InputStream input = downloadConn.getInputStream()) {
      manifestBytes = readAllBytes(input);
    }
    finally {
      downloadConn.disconnect();
    }

    // Step 2: Parse manifest to find referenced blobs and push them first
    // Docker registries require all referenced blobs to exist before accepting a manifest
    String manifestJson = new String(manifestBytes, "UTF-8");
    List<String> blobDigests = extractDockerBlobDigests(manifestJson, contentType);

    for (String blobDigest : blobDigests) {
      String blobPath = "v2/" + imageName + "/blobs/" + blobDigest;
      log.info("Pre-pushing Docker blob for manifest {}: {}", filePath, blobDigest);
      try {
        promoteDockerBlob(sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl, null, null);
      }
      catch (IOException e) {
        // Blob might already exist, or push might fail - log but continue
        // The manifest push will fail with BLOB_UNKNOWN if a blob is truly missing
        log.warn("Failed to pre-push blob {} for manifest {}: {}", blobDigest, filePath, e.getMessage());
      }
    }

    // Step 3: Push manifest to target via Docker v2 API
    URL pushUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/v2/" + imageName + "/manifests/" + tag);
    HttpURLConnection pushConn = (HttpURLConnection) pushUrl.openConnection();
    pushConn.setRequestMethod("PUT");
    pushConn.setDoOutput(true);
    pushConn.setConnectTimeout(TIMEOUT_MS);
    pushConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(pushConn, cookieHeader, csrfToken);
    pushConn.setRequestProperty("Content-Type", contentType);

    try (OutputStream output = pushConn.getOutputStream()) {
      output.write(manifestBytes);
      output.flush();
    }

    int pushCode = pushConn.getResponseCode();
    String pushMsg = "";
    if (pushCode >= 400) {
      pushMsg = readErrorResponse(pushConn);
    }
    pushConn.disconnect();

    if (pushCode < 200 || pushCode > 299) {
      throw new IOException("Push manifest to " + targetRepo + "/v2/" + imageName + "/manifests/" + tag +
          " failed: HTTP " + pushCode + " - " + pushMsg);
    }

    log.info("Successfully promoted Docker manifest: {}/v2/{}/manifests/{} -> {}",
        sourceRepo, imageName, tag, targetRepo);

    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(filePath, "image");
    item.setSourceMd5(sourceMd5);
    item.setTargetMd5(targetMd5);
    item.setStatus("success");
    return item;
  }

  /**
   * Promote a Docker blob using Docker v2 API.
   * Downloads the blob from source, then pushes it to target via the blob upload process.
   * @param filePath full path including "v2/" prefix, e.g. "v2/myapp/blobs/sha256:abc"
   */
  private PromotionTaskResult.FileItem promoteDockerBlob(
      final String sourceRepo,
      final String targetRepo,
      final String filePath,
      final String cookieHeader,
      final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5,
      final String targetMd5) throws IOException
  {
    // Parse path: v2/<image>/blobs/<digest>
    String pathWithoutV2 = filePath.startsWith("v2/") ? filePath.substring(3) : filePath;
    int blobsIdx = pathWithoutV2.indexOf("/blobs/");
    if (blobsIdx < 0) {
      throw new IOException("Invalid Docker blob path: " + filePath);
    }
    String imageName = pathWithoutV2.substring(0, blobsIdx);
    String digest = pathWithoutV2.substring(blobsIdx + "/blobs/".length());

    // Step 1: Initiate blob upload - POST /v2/<name>/blobs/uploads/
    URL initUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/v2/" + imageName + "/blobs/uploads/");
    HttpURLConnection initConn = (HttpURLConnection) initUrl.openConnection();
    initConn.setRequestMethod("POST");
    initConn.setConnectTimeout(TIMEOUT_MS);
    initConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(initConn, cookieHeader, csrfToken);

    int initCode = initConn.getResponseCode();
    if (initCode != 202) {
      String errorMsg = readErrorResponse(initConn);
      initConn.disconnect();

      // If blob already exists (HTTP 409 Conflict or 202 Accepted), skip
      if (initCode == 409 || initCode == 202) {
        log.info("Docker blob already exists in target, skipping: {}/{}", targetRepo, digest);
        PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(filePath, "image");
        item.setStatus("skipped");
        return item;
      }
      throw new IOException("Initiate blob upload to " + targetRepo + " failed: HTTP " + initCode + " - " + errorMsg);
    }

    // Get the upload location from Location header
    String uploadLocation = initConn.getHeaderField("Location");
    initConn.disconnect();

    if (uploadLocation == null || uploadLocation.isEmpty()) {
      throw new IOException("No Location header in blob upload initiation response from " + targetRepo);
    }

    // Make upload location absolute if relative
    if (!uploadLocation.startsWith("http")) {
      uploadLocation = nexusBaseUrl + "/repository/" + targetRepo + uploadLocation;
    }

    // Step 2: Download blob from source
    URL sourceUrl = new URL(nexusBaseUrl + "/repository/" + sourceRepo + "/" + filePath);
    HttpURLConnection downloadConn = (HttpURLConnection) sourceUrl.openConnection();
    downloadConn.setRequestMethod("GET");
    downloadConn.setConnectTimeout(TIMEOUT_MS);
    downloadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(downloadConn, cookieHeader, csrfToken);

    int downloadCode = downloadConn.getResponseCode();
    if (downloadCode != 200) {
      String errorMsg = readErrorResponse(downloadConn);
      downloadConn.disconnect();
      throw new IOException("Download blob from " + sourceRepo + "/" + filePath +
          " failed: HTTP " + downloadCode + " - " + errorMsg);
    }

    // Step 3: Upload blob to target via PUT to the upload location
    // Append digest parameter to the upload location
    String putUrl = uploadLocation;
    if (putUrl.contains("?")) {
      putUrl += "&digest=" + java.net.URLEncoder.encode(digest, "UTF-8");
    } else {
      putUrl += "?digest=" + java.net.URLEncoder.encode(digest, "UTF-8");
    }

    HttpURLConnection putConn = (HttpURLConnection) new URL(putUrl).openConnection();
    putConn.setRequestMethod("PUT");
    putConn.setDoOutput(true);
    putConn.setConnectTimeout(TIMEOUT_MS);
    putConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(putConn, cookieHeader, csrfToken);
    putConn.setRequestProperty("Content-Type", "application/octet-stream");
    putConn.setChunkedStreamingMode(64 * 1024);

    try (InputStream input = downloadConn.getInputStream();
         OutputStream output = putConn.getOutputStream()) {
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

    int putCode = putConn.getResponseCode();
    String putMsg = "";
    if (putCode >= 400) {
      putMsg = readErrorResponse(putConn);
    }
    putConn.disconnect();

    if (putCode < 200 || putCode > 299) {
      throw new IOException("Upload blob to " + targetRepo + " failed: HTTP " + putCode + " - " + putMsg);
    }

    log.info("Successfully promoted Docker blob: {}/{} -> {}", sourceRepo, digest, targetRepo);

    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(filePath, "image");
    item.setSourceMd5(sourceMd5);
    item.setTargetMd5(targetMd5);
    item.setStatus("success");
    return item;
  }

  /**
   * Extract blob digests referenced by a Docker manifest.
   * Supports Docker v2 manifest, manifest list, and OCI manifest formats.
   */
  private List<String> extractDockerBlobDigests(final String manifestJson, final String contentType) {
    List<String> digests = new ArrayList<>();
    if (manifestJson == null || manifestJson.isEmpty()) return digests;

    // Extract config digest
    String configDigest = extractJsonKeyValue(manifestJson, "config", "digest");
    if (configDigest != null && !configDigest.isEmpty()) {
      digests.add(configDigest);
    }

    // Extract layer digests from "layers" array
    extractArrayValues(manifestJson, "layers", "digest", digests);

    // For manifest list (fat manifest), extract manifests array
    if (contentType != null && contentType.contains("manifest.list")) {
      extractArrayValues(manifestJson, "manifests", "digest", digests);
    }

    // Also try "fsLayers" for v1 manifests
    extractArrayValues(manifestJson, "fsLayers", "blobSum", digests);

    log.debug("Extracted {} blob digests from manifest (contentType={})", digests.size(), contentType);
    return digests;
  }

  /**
   * Extract values from a JSON array of objects.
   * e.g., from {"layers": [{"digest": "sha256:abc"}, {"digest": "sha256:def"}]}
   * extracts ["sha256:abc", "sha256:def"]
   */
  private void extractArrayValues(final String json, final String arrayKey, final String valueKey,
      final List<String> results) {
    // Find the array: "arrayKey" : [
    String arrayPattern = "\"" + arrayKey + "\"";
    int arrayIdx = json.indexOf(arrayPattern);
    if (arrayIdx < 0) return;

    // Find the opening bracket
    int openBracket = json.indexOf('[', arrayIdx);
    if (openBracket < 0) return;

    // Find matching closing bracket
    int depth = 1;
    int closeBracket = openBracket + 1;
    while (closeBracket < json.length() && depth > 0) {
      char c = json.charAt(closeBracket);
      if (c == '[') depth++;
      else if (c == ']') depth--;
      closeBracket++;
    }

    String arrayContent = json.substring(openBracket, closeBracket);

    // Find all occurrences of "valueKey" : "value"
    String valuePattern = "\"" + valueKey + "\"";
    int searchIdx = 0;
    while (searchIdx < arrayContent.length()) {
      int valueIdx = arrayContent.indexOf(valuePattern, searchIdx);
      if (valueIdx < 0) break;

      // Find the colon after the key
      int colonIdx = arrayContent.indexOf(':', valueIdx + valuePattern.length());
      if (colonIdx < 0) break;

      // Find the opening quote of the value
      int openQuote = arrayContent.indexOf('"', colonIdx + 1);
      if (openQuote < 0) break;

      // Find the closing quote
      int closeQuote = arrayContent.indexOf('"', openQuote + 1);
      if (closeQuote < 0) break;

      String value = arrayContent.substring(openQuote + 1, closeQuote);
      if (!value.isEmpty()) {
        results.add(value);
      }

      searchIdx = closeQuote + 1;
    }
  }

  /**
   * Extract a value from a nested JSON object by finding the parent key and then the child key.
   * e.g., extractJsonKeyValue(json, "config", "digest") finds "config":{"digest":"sha256:abc"}
   * and returns "sha256:abc"
   */
  private String extractJsonKeyValue(final String json, final String parentKey, final String childKey) {
    String parentPattern = "\"" + parentKey + "\"";
    int parentIdx = json.indexOf(parentPattern);
    if (parentIdx < 0) return null;

    // Find the opening brace of the parent object
    int openBrace = json.indexOf('{', parentIdx + parentPattern.length());
    if (openBrace < 0) return null;

    // Find matching closing brace
    int depth = 1;
    int closeBrace = openBrace + 1;
    while (closeBrace < json.length() && depth > 0) {
      char c = json.charAt(closeBrace);
      if (c == '{') depth++;
      else if (c == '}') depth--;
      closeBrace++;
    }

    String parentContent = json.substring(openBrace, closeBrace);

    // Find the child key within the parent object
    String childPattern = "\"" + childKey + "\"";
    int childIdx = parentContent.indexOf(childPattern);
    if (childIdx < 0) return null;

    int colonIdx = parentContent.indexOf(':', childIdx + childPattern.length());
    if (colonIdx < 0) return null;

    int openQuote = parentContent.indexOf('"', colonIdx + 1);
    if (openQuote < 0) return null;

    int closeQuote = parentContent.indexOf('"', openQuote + 1);
    if (closeQuote < 0) return null;

    return parentContent.substring(openQuote + 1, closeQuote);
  }

  private byte[] readAllBytes(final InputStream input) throws IOException {
    byte[] buffer = new byte[8192];
    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
    int bytesRead;
    while ((bytesRead = input.read(buffer)) != -1) {
      baos.write(buffer, 0, bytesRead);
    }
    return baos.toByteArray();
  }

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }
}
