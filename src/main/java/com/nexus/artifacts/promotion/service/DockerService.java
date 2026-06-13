package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

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
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.sonatype.nexus.security.SecurityHelper;

import com.google.common.hash.HashCode;

import com.nexus.artifacts.promotion.model.DockerImageInfo;
import com.nexus.artifacts.promotion.model.DockerImageListResponse;
import com.nexus.artifacts.promotion.model.DockerImageRequest;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;

/**
 * Service for Docker image-specific operations.
 *
 * Docker images in Nexus have a specific structure:
 * - Components represent image names (e.g., "myapp/backend")
 * - Assets are stored as:
 *   - manifests/<tag>  → Docker manifest
 *   - blobs/sha256/<digest> → Docker blob (config + layers)
 *
 * This service provides efficient Docker image operations by:
 * 1. Using Nexus Search/Components API to list images and tags
 * 2. Parsing manifests to identify all referenced blobs
 * 3. Only syncing/promoting the blobs actually referenced by the manifest
 * 4. Supporting batch operations for all tags of an image
 */
@Named
@Singleton
public class DockerService {

  private static final Logger log = LoggerFactory.getLogger(DockerService.class);

  /** Maximum time (ms) to keep completed task results */
  private static final long TASK_RESULT_TTL_MS = 30 * 60 * 1000L;

  /** Connection/read timeout in milliseconds */
  private static final int TIMEOUT_MS = 300_000;

  /** Buffer size for streaming */
  private static final int BUFFER_SIZE = 8192;

  /** Nexus local base URL for internal API calls */
  private static final String LOCAL_NEXUS_BASE = "http://localhost:8081";

  /** Default admin credentials for internal API calls */
  private static final String DEFAULT_ADMIN_USERNAME = "admin";
  private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

  private volatile String adminUsername = DEFAULT_ADMIN_USERNAME;
  private volatile String adminPassword = DEFAULT_ADMIN_PASSWORD;

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;

  private final Map<String, PromotionTaskResult> promotionTaskResults = new ConcurrentHashMap<>();
  private final Map<String, SyncTaskInfo> syncTaskInfos = new ConcurrentHashMap<>();

  @Inject
  public DockerService(final RepositoryManager repositoryManager,
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

  /**
   * Update admin credentials from capability configuration.
   */
  public void updateAdminCredentials(final String username, final String password) {
    if (username != null && !username.isEmpty()) {
      this.adminUsername = username;
    }
    if (password != null && !password.isEmpty()) {
      this.adminPassword = password;
    }
  }

  private String getAdminAuth() {
    return adminUsername + ":" + adminPassword;
  }

  // ==================== Docker Image Listing ====================

  /**
   * List all Docker images in a repository with their tags.
   * Uses Nexus Components API (docker format) which provides image names and versions (tags).
   */
  public DockerImageListResponse listDockerImages(final String repositoryName) {
    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + repositoryName);
    }

    DockerImageListResponse response = new DockerImageListResponse();
    response.setRepository(repositoryName);
    response.setFormat("docker");

    // Map: imageName -> DockerImageInfo
    Map<String, DockerImageInfo> imageMap = new LinkedHashMap<>();

    try {
      String apiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/components?repository=" + repositoryName;
      String continuationToken = null;
      String effectiveAuth = getAdminAuth();

      do {
        String url = apiUrl;
        if (continuationToken != null) {
          url += "&continuationToken=" + continuationToken;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));

        if (conn.getResponseCode() != 200) {
          log.warn("Components API returned HTTP {} for {}", conn.getResponseCode(), url);
          conn.disconnect();
          break;
        }

        String json = readResponse(conn);
        parseDockerComponents(json, imageMap);
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());
    }
    catch (Exception e) {
      log.error("Failed to list Docker images: {}", e.getMessage(), e);
    }

    response.setImages(new ArrayList<>(imageMap.values()));
    response.setTotalCount(imageMap.size());
    return response;
  }

  /**
   * List tags for a specific Docker image.
   */
  public List<String> listDockerTags(final String repositoryName, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
      String effectiveAuth = getAdminAuth();
      // Use search API to find components matching the image name
      String apiUrl = LOCAL_NEXUS_BASE + "/service/rest/v1/components?repository=" + repositoryName;
      String continuationToken = null;

      do {
        String url = apiUrl;
        if (continuationToken != null) {
          url += "&continuationToken=" + continuationToken;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));

        if (conn.getResponseCode() != 200) {
          conn.disconnect();
          break;
        }

        String json = readResponse(conn);
        extractTagsForImage(json, imageName, tags);
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());
    }
    catch (Exception e) {
      log.error("Failed to list Docker tags for {}/{}: {}", repositoryName, imageName, e.getMessage());
    }
    return tags;
  }

  // ==================== Docker Promotion ====================

  /**
   * Promote Docker images from source to target repository.
   * If request.isAllTags() → promote all tags of the image.
   * If request.getTags() is specified → promote only those tags.
   *
   * For each image:tag, the flow is:
   * 1. Download manifest from source
   * 2. Parse manifest to find all referenced blob digests
   * 3. For each referenced blob: MD5 compare → skip if match, else download+upload
   * 4. Upload manifest to target
   *
   * This is much more efficient than the generic directory promotion because:
   * - It only transfers blobs referenced by the manifest, not all blobs in the repo
   * - It processes one logical unit (image:tag) at a time
   */
  public String promoteDockerImage(final DockerImageRequest request,
                                    final String cookieHeader,
                                    final String csrfToken,
                                    final String nexusBaseUrl)
  {
    request.validate();
    if (request.getTargetRepository() == null || request.getTargetRepository().trim().isEmpty()) {
      throw new IllegalArgumentException("targetRepository is required for promotion");
    }
    permissionChecker.checkTargetWritePermission(request.getTargetRepository());

    String username = permissionChecker.getCurrentUsername();
    final String taskId = "docker-promo-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;

    return taskExecutor.submitPromotionTask(() -> {
      if (threadState != null) { threadState.bind(); }
      try {
        PromotionTaskResult result = new PromotionTaskResult();
        result.setSourceRepository(request.getSourceRepository());
        result.setTargetRepository(request.getTargetRepository());
        result.setUsername(username);
        result.setStartTime(System.currentTimeMillis());
        result.setTaskId(taskId);
        result.setStatus(TaskStatus.RUNNING.getValue());
        promotionTaskResults.put(taskId, copyPromotionResult(result));

        List<PromotionTaskResult.FileItem> promotedItems = new ArrayList<>();

        try {
          // Determine which tags to promote
          List<String> tags = request.getTags();
          if (request.isAllTags()) {
            tags = listDockerTags(request.getSourceRepository(), request.getImage());
            log.info("Docker promotion: found {} tags for image {} in {}", tags.size(), request.getImage(), request.getSourceRepository());
          }

          if (tags.isEmpty()) {
            throw new IOException("No tags found for image " + request.getImage() + " in " + request.getSourceRepository());
          }

          // Promote each tag
          for (String tag : tags) {
            try {
              List<PromotionTaskResult.FileItem> tagItems = promoteDockerTag(
                  request.getSourceRepository(), request.getTargetRepository(),
                  request.getImage(), tag, cookieHeader, csrfToken, nexusBaseUrl);
              promotedItems.addAll(tagItems);
            }
            catch (Exception e) {
              log.error("Failed to promote {}:{}: {}", request.getImage(), tag, e.getMessage());
              String manifestPath = "v2/" + request.getImage() + "/manifests/" + tag;
              PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(manifestPath, "image");
              failedItem.setStatus("failed");
              failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
              promotedItems.add(failedItem);
            }
            updatePromotionTaskProgress(result, promotedItems);
          }

          result.setItems(promotedItems);
          result.setStatus(TaskStatus.COMPLETED.getValue());
          result.setEndTime(System.currentTimeMillis());

          long skippedCount = promotedItems.stream().filter(f -> "skipped".equals(f.getStatus())).count();
          long promotedCount = promotedItems.size() - skippedCount;
          log.info("Docker promotion task {} completed: {} items promoted, {} skipped, image={}",
              taskId, promotedCount, skippedCount, request.getImage());
        }
        catch (Exception e) {
          log.error("Docker promotion task {} failed: {}", taskId, e.getMessage(), e);
          result.setStatus(TaskStatus.FAILED.getValue());
          result.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          result.setEndTime(System.currentTimeMillis());
        }

        promotionTaskResults.put(taskId, copyPromotionResult(result));

        return new TaskExecutorService.PromotionTaskCallback() {
          @Override public String getTaskId() { return taskId; }
          @Override public TaskStatus getStatus() { return TaskStatus.fromValue(result.getStatus()); }
          @Override public String getErrorMessage() { return result.getErrorMessage(); }
        };
      }
      finally {
        if (threadState != null) { threadState.clear(); }
      }
    }, String.format("Docker promote %s from %s to %s", request.getImage(),
        request.getSourceRepository(), request.getTargetRepository()), taskId);
  }

  /**
   * Promote a single Docker image:tag from source to target.
   *
   * Flow:
   * 1. Download manifest from source
   * 2. Parse manifest to extract all blob digests
   * 3. For each blob: compare MD5 → skip if match, else download+upload
   * 4. Upload manifest to target
   *
   * Returns list of FileItems for manifest and all blobs.
   */
  private List<PromotionTaskResult.FileItem> promoteDockerTag(
      final String sourceRepo, final String targetRepo,
      final String image, final String tag,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    List<PromotionTaskResult.FileItem> items = new ArrayList<>();
    String manifestPath = "v2/" + image + "/manifests/" + tag;

    log.info("[DOCKER-PROMO] Promoting {}:{} (manifest path: {})", image, tag, manifestPath);

    // Step 1: Download manifest from source
    URL manifestSourceUrl = new URL(nexusBaseUrl + "/repository/" + sourceRepo + "/" + manifestPath);
    HttpURLConnection manifestConn = (HttpURLConnection) manifestSourceUrl.openConnection();
    manifestConn.setRequestMethod("GET");
    manifestConn.setConnectTimeout(TIMEOUT_MS);
    manifestConn.setReadTimeout(TIMEOUT_MS);
    manifestConn.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json");
    setAuthHeaders(manifestConn, cookieHeader, csrfToken);

    int manifestResponseCode = manifestConn.getResponseCode();
    if (manifestResponseCode != 200) {
      String error = readErrorResponse(manifestConn);
      manifestConn.disconnect();
      throw new IOException("Failed to download manifest " + manifestPath + ": HTTP " + manifestResponseCode + " - " + error);
    }

    String manifestContent = readStream(manifestConn.getInputStream());
    String manifestDigest = manifestConn.getHeaderField("Docker-Content-Digest");
    manifestConn.disconnect();

    // Step 2: Parse manifest to extract blob digests
    Set<String> blobDigests = parseManifestBlobs(manifestContent);
    log.info("[DOCKER-PROMO] Manifest for {}:{} references {} blobs", image, tag, blobDigests.size());

    // Step 3: Promote each blob (MD5 incremental check)
    for (String digest : blobDigests) {
      String blobPath = "v2/" + image + "/blobs/" + digest;
      try {
        PromotionTaskResult.FileItem blobItem = promoteDockerBlob(
            sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl);
        items.add(blobItem);
      }
      catch (Exception e) {
        log.error("Failed to promote blob {} for {}:{}: {}", digest, image, tag, e.getMessage());
        PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(blobPath, "image");
        failedItem.setStatus("failed");
        failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        items.add(failedItem);
      }
    }

    // Step 4: Upload manifest to target
    try {
      PromotionTaskResult.FileItem manifestItem = uploadDockerManifest(
          targetRepo, manifestPath, manifestContent, cookieHeader, csrfToken, nexusBaseUrl);
      items.add(manifestItem);
    }
    catch (Exception e) {
      log.error("Failed to upload manifest for {}:{}: {}", image, tag, e.getMessage());
      PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(manifestPath, "image");
      failedItem.setStatus("failed");
      failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
      items.add(failedItem);
    }

    return items;
  }

  /**
   * Promote a single Docker blob with MD5 incremental check.
   */
  private PromotionTaskResult.FileItem promoteDockerBlob(
      final String sourceRepo, final String targetRepo,
      final String blobPath,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    // MD5 incremental check
    String sourceMd5 = getAssetMd5(sourceRepo, blobPath);
    String targetMd5 = getAssetMd5(targetRepo, blobPath);

    if (sourceMd5 != null && targetMd5 != null && sourceMd5.equalsIgnoreCase(targetMd5)) {
      log.debug("Skipping blob promotion (MD5 match): {}", blobPath);
      PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(blobPath, "image");
      item.setStatus("skipped");
      item.setSourceMd5(sourceMd5);
      item.setTargetMd5(targetMd5);
      return item;
    }

    // Download from source, upload to target
    URL sourceUrl = new URL(nexusBaseUrl + "/repository/" + sourceRepo + "/" + blobPath);
    HttpURLConnection downloadConn = (HttpURLConnection) sourceUrl.openConnection();
    downloadConn.setRequestMethod("GET");
    downloadConn.setConnectTimeout(TIMEOUT_MS);
    downloadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(downloadConn, cookieHeader, csrfToken);

    int responseCode = downloadConn.getResponseCode();
    if (responseCode != 200) {
      String error = readErrorResponse(downloadConn);
      downloadConn.disconnect();
      throw new IOException("Download blob failed: HTTP " + responseCode + " - " + error);
    }

    // Upload to target
    URL targetUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + blobPath);
    HttpURLConnection uploadConn = (HttpURLConnection) targetUrl.openConnection();
    uploadConn.setRequestMethod("PUT");
    uploadConn.setDoOutput(true);
    uploadConn.setConnectTimeout(TIMEOUT_MS);
    uploadConn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(uploadConn, cookieHeader, csrfToken);

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
    String uploadMsg = uploadResponse >= 400 ? readErrorResponse(uploadConn) : "";
    uploadConn.disconnect();

    if (uploadResponse < 200 || uploadResponse > 299) {
      throw new IOException("Upload blob failed: HTTP " + uploadResponse + " - " + uploadMsg);
    }

    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(blobPath, "image");
    item.setSourceMd5(sourceMd5);
    item.setTargetMd5(targetMd5);
    return item;
  }

  /**
   * Upload a Docker manifest to target repository.
   */
  private PromotionTaskResult.FileItem uploadDockerManifest(
      final String targetRepo, final String manifestPath,
      final String manifestContent,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    URL targetUrl = new URL(nexusBaseUrl + "/repository/" + targetRepo + "/" + manifestPath);
    HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
    conn.setRequestMethod("PUT");
    conn.setDoOutput(true);
    conn.setConnectTimeout(TIMEOUT_MS);
    conn.setReadTimeout(TIMEOUT_MS);
    setAuthHeaders(conn, cookieHeader, csrfToken);
    conn.setRequestProperty("Content-Type", "application/vnd.docker.distribution.manifest.v2+json");

    try (OutputStream output = conn.getOutputStream()) {
      output.write(manifestContent.getBytes("UTF-8"));
      output.flush();
    }

    int responseCode = conn.getResponseCode();
    String errorMsg = responseCode >= 400 ? readErrorResponse(conn) : "";
    conn.disconnect();

    if (responseCode < 200 || responseCode > 299) {
      throw new IOException("Upload manifest failed: HTTP " + responseCode + " - " + errorMsg);
    }

    PromotionTaskResult.FileItem item = new PromotionTaskResult.FileItem(manifestPath, "image");
    return item;
  }

  // ==================== Docker Sync (Proxy Repos) ====================

  /**
   * Sync Docker images from a proxy repository's remote.
   * If request.isAllTags() → sync all tags of the image.
   * If request.getTags() is specified → sync only those tags.
   *
   * For each image:tag:
   * 1. Get manifest from remote (via ViewFacet.dispatch or Docker Registry API)
   * 2. Parse manifest to find all referenced blob digests
   * 3. Sync each blob (delete cache + invalidate neg cache + ViewFacet.dispatch)
   * 4. Sync manifest
   */
  public String syncDockerImage(final DockerImageRequest request) {
    request.validate();
    permissionChecker.checkSyncPermission(request.getSourceRepository(), request.getFormat());

    if (!taskExecutor.hasSyncQueueCapacity()) {
      throw new IllegalStateException("Sync queue is full. Please wait for existing tasks to complete.");
    }

    String username = permissionChecker.getCurrentUsername();
    Subject subject = securityHelper.subject();
    final SubjectThreadState threadState = (subject != null && subject.isAuthenticated())
        ? new SubjectThreadState(subject) : null;
    final String preTaskId = "docker-sync-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    return taskExecutor.submitSyncTask(() -> {
      if (threadState != null) { threadState.bind(); }
      try {
        SyncTaskInfo taskInfo = new SyncTaskInfo();
        taskInfo.setTaskId(preTaskId);
        taskInfo.setSourceRepository(request.getSourceRepository());
        taskInfo.setTargetRepository(request.getSourceRepository()); // proxy syncs to itself
        taskInfo.setPath("v2/" + request.getImage());
        taskInfo.setDirectory(true);
        taskInfo.setFormat(request.getFormat());
        taskInfo.setUsername(username);
        taskInfo.setStartTime(System.currentTimeMillis());
        taskInfo.setStatus(TaskStatus.RUNNING);

        try {
          Repository repo = repositoryManager.get(request.getSourceRepository());
          if (repo == null) {
            throw new IllegalArgumentException("Repository not found: " + request.getSourceRepository());
          }
          if (!"proxy".equals(repo.getType().getValue())) {
            throw new IllegalArgumentException("Repository is not a proxy type: " + request.getSourceRepository());
          }

          // Determine which tags to sync
          List<String> tags = request.getTags();
          if (request.isAllTags()) {
            tags = listDockerTagsRemote(repo, request.getImage());
            log.info("Docker sync: found {} tags for image {} in remote", tags.size(), request.getImage());
          }

          if (tags.isEmpty()) {
            throw new IOException("No tags found for image " + request.getImage() + " in remote repository");
          }

          cacheManager.createTaskCache(preTaskId);

          List<SyncTaskInfo.SyncFileDetail> syncedFiles = new ArrayList<>();

          for (String tag : tags) {
            try {
              List<SyncTaskInfo.SyncFileDetail> tagFiles = syncDockerTag(repo, request.getImage(), tag);
              syncedFiles.addAll(tagFiles);
            }
            catch (Exception e) {
              log.error("Failed to sync {}:{}: {}", request.getImage(), tag, e.getMessage());
              String manifestPath = "v2/" + request.getImage() + "/manifests/" + tag;
              SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
              failedDetail.setStatus("failed");
              failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
              syncedFiles.add(failedDetail);
            }
          }

          taskInfo.setFileDetails(syncedFiles);
          taskInfo.setStatus(TaskStatus.COMPLETED);
          taskInfo.setEndTime(System.currentTimeMillis());

          long skippedCount = syncedFiles.stream().filter(f -> "skipped".equals(f.getStatus())).count();
          long syncedCount = syncedFiles.size() - skippedCount;
          taskInfo.setResult("Synced " + syncedCount + " items" +
              (skippedCount > 0 ? ", skipped " + skippedCount + " (unchanged)" : ""));

          log.info("Docker sync task completed: {} items synced, {} skipped for {}",
              syncedCount, skippedCount, request.getImage());
        }
        catch (Exception e) {
          log.error("Docker sync task failed: {}", e.getMessage(), e);
          taskInfo.setStatus(TaskStatus.FAILED);
          taskInfo.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
          taskInfo.setEndTime(System.currentTimeMillis());
        }

        syncTaskInfos.put(preTaskId, taskInfo);

        return new TaskExecutorService.SyncTaskCallback() {
          @Override public String getTaskId() { return preTaskId; }
          @Override public TaskStatus getStatus() { return taskInfo.getStatus(); }
          @Override public String getErrorMessage() { return taskInfo.getErrorMessage(); }
        };
      }
      finally {
        if (threadState != null) { threadState.clear(); }
      }
    }, String.format("Docker sync %s from %s", request.getImage(), request.getSourceRepository()),
        request.getSourceRepository(), "v2/" + request.getImage(), preTaskId);
  }

  /**
   * Sync a single Docker image:tag from remote proxy.
   *
   * Flow:
   * 1. Sync manifest via ViewFacet.dispatch (forces remote fetch)
   * 2. Read manifest content from local cache
   * 3. Parse manifest to extract all blob digests
   * 4. Sync each referenced blob
   */
  private List<SyncTaskInfo.SyncFileDetail> syncDockerTag(
      final Repository repo, final String image, final String tag) throws Exception
  {
    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();
    String manifestPath = "v2/" + image + "/manifests/" + tag;

    log.info("[DOCKER-SYNC] Syncing {}:{} (manifest path: {})", image, tag, manifestPath);

    // Step 1: Sync manifest - delete cached, invalidate neg cache, dispatch
    SyncTaskInfo.SyncFileDetail manifestDetail = syncDockerAsset(repo, manifestPath);
    details.add(manifestDetail);

    // Step 2: Read manifest content from local cache to parse blob references
    String manifestContent = readManifestContentFromCache(repo, manifestPath);

    if (manifestContent == null || manifestContent.isEmpty()) {
      log.warn("Could not read manifest content for {}:{}, cannot determine blob dependencies", image, tag);
      return details;
    }

    // Step 3: Parse manifest to extract blob digests
    Set<String> blobDigests = parseManifestBlobs(manifestContent);
    log.info("[DOCKER-SYNC] Manifest for {}:{} references {} blobs", image, tag, blobDigests.size());

    // Step 4: Sync each referenced blob
    for (String digest : blobDigests) {
      String blobPath = "v2/" + image + "/blobs/" + digest;
      try {
        SyncTaskInfo.SyncFileDetail blobDetail = syncDockerAsset(repo, blobPath);
        details.add(blobDetail);
      }
      catch (Exception e) {
        log.error("Failed to sync blob {} for {}:{}: {}", digest, image, tag, e.getMessage());
        SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(blobPath, "image");
        failedDetail.setStatus("failed");
        failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        details.add(failedDetail);
      }
    }

    return details;
  }

  /**
   * Sync a single Docker asset (manifest or blob) via ViewFacet.dispatch.
   * Includes MD5 incremental check, cache deletion, and negative cache invalidation.
   */
  private SyncTaskInfo.SyncFileDetail syncDockerAsset(final Repository repo, final String assetPath) throws Exception {
    SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(assetPath, "image");

    // Extract auth from proxy repo configuration
    String[] repoAuth = extractAuthFromRepo(repo);

    // Incremental check: compare MD5 of remote and local
    String remoteMd5 = getRemoteAssetMd5(repo, assetPath, repoAuth);
    String localMd5 = getLocalAssetMd5(repo.getName(), assetPath);
    detail.setRemoteMd5(remoteMd5);
    detail.setLocalMd5(localMd5);

    if (remoteMd5 != null && localMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
      log.debug("Skipping Docker asset sync (MD5 match): {}/{}", repo.getName(), assetPath);
      detail.setStatus("skipped");
      return detail;
    }

    // Delete cached asset + invalidate negative cache + dispatch GET
    deleteCachedAssetInternal(repo, assetPath);
    invalidateNegativeCache(repo, assetPath);

    ViewFacet viewFacet = repo.facet(ViewFacet.class);
    if (viewFacet != null) {
      Request request = new Request.Builder()
          .action("GET")
          .path("/" + assetPath)
          .build();
      Response response = viewFacet.dispatch(request);

      if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
        log.debug("Successfully synced Docker asset {} (HTTP {})", assetPath, response.getStatus().getCode());
        detail.setStatus("success");
      }
      else {
        log.warn("Failed to sync Docker asset {}: HTTP {}", assetPath, response.getStatus().getCode());
        detail.setStatus("failed");
        detail.setErrorMessage("HTTP " + response.getStatus().getCode());
      }
    }
    else {
      detail.setStatus("failed");
      detail.setErrorMessage("ViewFacet not available");
    }

    return detail;
  }

  /**
   * Read manifest content from the local cache of a proxy repository.
   */
  private String readManifestContentFromCache(final Repository repo, final String manifestPath) {
    try {
      StorageTx tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      try {
        Bucket bucket = tx.findBucket(repo);
        Asset asset = tx.findAssetWithProperty("name", manifestPath, bucket);
        if (asset == null && !manifestPath.startsWith("/")) {
          asset = tx.findAssetWithProperty("name", "/" + manifestPath, bucket);
        }
        if (asset == null) {
          log.debug("Manifest not found in cache: {}/{}", repo.getName(), manifestPath);
          return null;
        }
        if (asset.requireBlobRef() != null) {
          Blob blob = tx.requireBlob(asset.requireBlobRef());
          if (blob != null) {
            InputStream is = blob.getInputStream();
            if (is != null) {
              try {
                return readStream(is);
              }
              finally {
                try { is.close(); } catch (Exception e) { /* ignore */ }
              }
            }
          }
        }
      }
      finally {
        tx.close();
      }
    }
    catch (Exception e) {
      log.debug("Failed to read manifest from cache for {}/{}: {}", repo.getName(), manifestPath, e.getMessage());
    }
    return null;
  }

  /**
   * List Docker tags from the remote repository (for proxy sync).
   * Tries Docker Registry V2 API first, then falls back to local listing.
   */
  private List<String> listDockerTagsRemote(final Repository repo, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
      // Try Docker Registry V2 API on the remote URL
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config != null && config.getAttributes() != null && config.getAttributes().containsKey("proxy")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> proxyAttrs = config.getAttributes().get("proxy");
        String remoteUrl = (String) proxyAttrs.get("remoteUrl");
        if (remoteUrl != null && !remoteUrl.isEmpty()) {
          if (!remoteUrl.endsWith("/")) {
            remoteUrl += "/";
          }

          String[] repoAuth = extractAuthFromRepo(repo);
          String authUsername = (repoAuth != null) ? repoAuth[0] : null;
          String authPassword = (repoAuth != null) ? repoAuth[1] : null;

          // Try Docker Registry V2 tags/list API
          String tagsUrl = remoteUrl + "v2/" + imageName + "/tags/list";
          HttpURLConnection conn = (HttpURLConnection) new URL(tagsUrl).openConnection();
          conn.setRequestMethod("GET");
          conn.setConnectTimeout(15_000);
          conn.setReadTimeout(30_000);
          conn.setRequestProperty("Accept", "application/json");

          if (authUsername != null && authPassword != null) {
            conn.setRequestProperty("Authorization", "Basic " + encodeAuth(authUsername + ":" + authPassword));
          }

          int code = conn.getResponseCode();
          if (code == 200) {
            String json = readResponse(conn);
            tags = parseDockerTagsList(json);
            log.info("Found {} tags for image {} via Docker Registry API", tags.size(), imageName);
            return tags;
          }
          conn.disconnect();
        }
      }
    }
    catch (Exception e) {
      log.debug("Docker Registry V2 API failed for {}: {}", imageName, e.getMessage());
    }

    // Fallback: list tags from local proxy cache (may not have all remote tags)
    log.info("Falling back to local tag listing for {}", imageName);
    return listDockerTags(repo.getName(), imageName);
  }

  // ==================== Manifest Parsing ====================

  /**
   * Parse a Docker manifest (v2 or OCI) to extract all referenced blob digests.
   *
   * Docker Manifest V2 Schema 2:
   * {
   *   "config": { "digest": "sha256:abc..." },
   *   "layers": [ { "digest": "sha256:def..." }, ... ]
   * }
   *
   * Docker Manifest List (fat manifest):
   * {
   *   "manifests": [ { "digest": "sha256:ghi...", "platform": {...} }, ... ]
   * }
   *
   * Returns set of digest strings (e.g., "sha256:abc123...") that need to be synced/promoted.
   */
  Set<String> parseManifestBlobs(final String manifestContent) {
    Set<String> digests = new HashSet<>();
    if (manifestContent == null || manifestContent.isEmpty()) {
      return digests;
    }

    // Extract "config.digest"
    String configDigest = extractDigestValue(manifestContent, "config");
    if (configDigest != null) {
      digests.add(configDigest);
    }

    // Extract "layers[].digest"
    digests.addAll(extractLayerDigests(manifestContent));

    // Check for manifest list (fat manifest) - "manifests[].digest"
    // These are sub-manifests for different platforms
    digests.addAll(extractManifestListDigests(manifestContent));

    return digests;
  }

  /**
   * Extract digest from a JSON object block, e.g., "config": { "digest": "sha256:abc" }
   */
  private String extractDigestValue(final String json, final String key) {
    try {
      String searchKey = "\"" + key + "\"";
      int keyIdx = json.indexOf(searchKey);
      if (keyIdx < 0) return null;

      // Find the object after the key
      int objStart = json.indexOf('{', keyIdx + searchKey.length());
      if (objStart < 0) return null;
      int objEnd = findMatchingBrace(json, objStart);
      if (objEnd < 0) return null;

      String obj = json.substring(objStart, objEnd + 1);
      return extractJsonValue(obj, "digest");
    }
    catch (Exception e) {
      return null;
    }
  }

  /**
   * Extract all layer digests from the "layers" array in a manifest.
   */
  private List<String> extractLayerDigests(final String json) {
    List<String> digests = new ArrayList<>();
    try {
      String layersArray = extractJsonArray(json, "layers");
      if (layersArray == null) return digests;

      int pos = 0;
      while (pos < layersArray.length()) {
        int objStart = layersArray.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(layersArray, objStart);
        if (objEnd < 0) break;

        String obj = layersArray.substring(objStart, objEnd + 1);
        String digest = extractJsonValue(obj, "digest");
        if (digest != null && !digest.isEmpty()) {
          digests.add(digest);
        }
        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract layer digests: {}", e.getMessage());
    }
    return digests;
  }

  /**
   * Extract all manifest digests from a manifest list (fat manifest for multi-platform images).
   */
  private List<String> extractManifestListDigests(final String json) {
    List<String> digests = new ArrayList<>();
    try {
      String manifestsArray = extractJsonArray(json, "manifests");
      if (manifestsArray == null) return digests;

      int pos = 0;
      while (pos < manifestsArray.length()) {
        int objStart = manifestsArray.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(manifestsArray, objStart);
        if (objEnd < 0) break;

        String obj = manifestsArray.substring(objStart, objEnd + 1);
        String digest = extractJsonValue(obj, "digest");
        if (digest != null && !digest.isEmpty()) {
          digests.add(digest);
        }
        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract manifest list digests: {}", e.getMessage());
    }
    return digests;
  }

  /**
   * Parse Docker tags/list API response.
   * { "name": "image", "tags": ["latest", "v1.0", ...] }
   */
  private List<String> parseDockerTagsList(final String json) {
    List<String> tags = new ArrayList<>();
    try {
      String tagsArray = extractJsonArray(json, "tags");
      if (tagsArray == null) return tags;

      int pos = 0;
      while (pos < tagsArray.length()) {
        // Find string values in the array
        int quoteStart = tagsArray.indexOf('"', pos);
        if (quoteStart < 0) break;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < tagsArray.length()) {
          char c = tagsArray.charAt(quoteEnd);
          if (c == '"' && tagsArray.charAt(quoteEnd - 1) != '\\') {
            break;
          }
          quoteEnd++;
        }
        if (quoteEnd >= tagsArray.length()) break;

        String tag = tagsArray.substring(quoteStart + 1, quoteEnd)
            .replace("\\\"", "\"").replace("\\\\", "\\");
        if (!tag.isEmpty()) {
          tags.add(tag);
        }
        pos = quoteEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to parse Docker tags list: {}", e.getMessage());
    }
    return tags;
  }

  // ==================== Task Status ====================

  public PromotionTaskResult getPromotionTaskResult(final String taskId) {
    cleanupExpiredPromotionTaskResults();
    PromotionTaskResult result = promotionTaskResults.get(taskId);
    if (result != null) {
      String statusStr = result.getStatus();
      if ("COMPLETED".equals(statusStr) || "FAILED".equals(statusStr)) {
        taskExecutor.cleanupPromotionTaskHandle(taskId);
      }
    }
    return result;
  }

  public SyncTaskInfo getSyncTaskInfo(final String taskId) {
    cleanupExpiredSyncTaskInfos();
    SyncTaskInfo info = syncTaskInfos.get(taskId);
    if (info != null) {
      TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
      if (status != null) {
        info.setStatus(status);
      }
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
        taskExecutor.cleanupSyncTaskHandle(taskId);
      }
    }
    return info;
  }

  private void cleanupExpiredPromotionTaskResults() {
    long now = System.currentTimeMillis();
    List<String> expired = new ArrayList<>();
    for (Map.Entry<String, PromotionTaskResult> entry : promotionTaskResults.entrySet()) {
      PromotionTaskResult r = entry.getValue();
      String s = r.getStatus();
      if (("COMPLETED".equals(s) || "FAILED".equals(s)) && r.getEndTime() > 0
          && (now - r.getEndTime()) > TASK_RESULT_TTL_MS) {
        expired.add(entry.getKey());
      }
    }
    for (String id : expired) {
      promotionTaskResults.remove(id);
      taskExecutor.cleanupPromotionTaskHandle(id);
    }
  }

  private void cleanupExpiredSyncTaskInfos() {
    long now = System.currentTimeMillis();
    List<String> expired = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : syncTaskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = info.getStatus();
      if ((status == TaskStatus.COMPLETED || status == TaskStatus.FAILED)
          && info.getEndTime() > 0 && (now - info.getEndTime()) > TASK_RESULT_TTL_MS) {
        expired.add(entry.getKey());
      }
    }
    for (String id : expired) {
      syncTaskInfos.remove(id);
      taskExecutor.cleanupSyncTaskHandle(id);
    }
  }

  // ==================== Component Parsing ====================

  /**
   * Parse Nexus Components API response for Docker format.
   * Each component = one image, component.group = image name, component.version = tag.
   */
  private void parseDockerComponents(final String json, final Map<String, DockerImageInfo> imageMap) {
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return;

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String componentObj = itemsSection.substring(objStart, objEnd + 1);

        // For Docker format: group = image name, version = tag
        String group = extractJsonValue(componentObj, "group");
        String name = extractJsonValue(componentObj, "name");
        String version = extractJsonValue(componentObj, "version");

        // Docker images: group is the namespace/path, name is the image name
        // Full image name = group + "/" + name (or just name if group is empty)
        String imageName;
        if (group != null && !group.isEmpty() && !group.equals(name)) {
          imageName = group + "/" + name;
        }
        else {
          imageName = name;
        }

        if (imageName != null && !imageName.isEmpty()) {
          DockerImageInfo info = imageMap.get(imageName);
          if (info == null) {
            info = new DockerImageInfo(imageName);
            imageMap.put(imageName, info);
          }
          if (version != null && !version.isEmpty()) {
            info.addTag(version);
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.warn("Failed to parse Docker components: {}", e.getMessage());
    }
  }

  /**
   * Extract tags for a specific image from Components API response.
   */
  private void extractTagsForImage(final String json, final String targetImage, final List<String> tags) {
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return;

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String componentObj = itemsSection.substring(objStart, objEnd + 1);

        String group = extractJsonValue(componentObj, "group");
        String name = extractJsonValue(componentObj, "name");
        String version = extractJsonValue(componentObj, "version");

        String imageName;
        if (group != null && !group.isEmpty() && !group.equals(name)) {
          imageName = group + "/" + name;
        }
        else {
          imageName = name;
        }

        if (targetImage.equals(imageName) && version != null && !version.isEmpty()) {
          if (!tags.contains(version)) {
            tags.add(version);
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.warn("Failed to extract tags for image {}: {}", targetImage, e.getMessage());
    }
  }

  // ==================== MD5 / Cache / Auth Helpers ====================

  private String getAssetMd5(final String repoName, final String assetPath) {
    try {
      Repository repo = repositoryManager.get(repoName);
      if (repo == null) return null;

      StorageTx tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      try {
        Bucket bucket = tx.findBucket(repo);
        Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);
        if (asset == null && !assetPath.startsWith("/")) {
          asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
        }
        if (asset == null) return null;

        try {
          HashCode md5Hash = asset.getChecksum(HashAlgorithm.MD5);
          if (md5Hash != null) {
            String md5 = md5Hash.toString();
            if (!md5.isEmpty()) return md5;
          }
        }
        catch (Exception ignored) {}

        try {
          if (asset.requireBlobRef() != null) {
            Blob blob = tx.requireBlob(asset.requireBlobRef());
            if (blob != null) {
              Map<String, String> headers = blob.getHeaders();
              String md5 = headers.get("Content-Hash-MD5");
              if (md5 == null || md5.isEmpty()) md5 = headers.get("content-hash-md5");
              if (md5 != null && !md5.isEmpty()) return md5;
            }
          }
        }
        catch (Exception ignored) {}
      }
      finally {
        tx.close();
      }
    }
    catch (Exception e) {
      log.debug("Failed to get MD5 for {}/{}: {}", repoName, assetPath, e.getMessage());
    }
    return null;
  }

  private String getRemoteAssetMd5(final Repository proxyRepo, final String assetPath, final String[] repoAuth) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = proxyRepo.getConfiguration();
      if (config == null) return null;
      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) return null;

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return null;

      String remoteRepoName = extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        Repository remoteRepo = repositoryManager.get(remoteRepoName);
        if (remoteRepo != null) {
          return getAssetMd5(remoteRepoName, assetPath);
        }
      }
    }
    catch (Exception e) {
      log.debug("Failed to get remote MD5 for {}/{}: {}", proxyRepo.getName(), assetPath, e.getMessage());
    }
    return null;
  }

  private String getLocalAssetMd5(final String repoName, final String assetPath) {
    return getAssetMd5(repoName, assetPath);
  }

  private void deleteCachedAssetInternal(final Repository repo, final String assetPath) {
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      Asset asset = tx.findAssetWithProperty("name", assetPath, bucket);
      if (asset == null && !assetPath.startsWith("/")) {
        asset = tx.findAssetWithProperty("name", "/" + assetPath, bucket);
      }
      if (asset != null) {
        log.debug("Deleting cached Docker asset: {}", assetPath);
        tx.deleteAsset(asset);
        tx.commit();
      }
    }
    catch (Exception e) {
      log.debug("Failed to delete cached asset {}: {}", assetPath, e.getMessage());
      if (tx != null) { try { tx.rollback(); } catch (Exception ex) { /* ignore */ } }
    }
    finally {
      if (tx != null) { try { tx.close(); } catch (Exception e) { /* ignore */ } }
    }
  }

  @SuppressWarnings("unchecked")
  private void invalidateNegativeCache(final Repository repo, final String assetPath) {
    try {
      Class<?> negCacheFacetClass;
      try {
        negCacheFacetClass = Class.forName("org.sonatype.nexus.repository.cache.NegativeCacheFacet");
      }
      catch (ClassNotFoundException e) { return; }

      Object negCacheFacet = repo.facet((Class) negCacheFacetClass);
      if (negCacheFacet != null) {
        try {
          java.lang.reflect.Method invalidateMethod = negCacheFacet.getClass().getMethod("invalidate", String.class);
          invalidateMethod.invoke(negCacheFacet, assetPath);
        }
        catch (NoSuchMethodException e) {
          try {
            java.lang.reflect.Method invalidateAllMethod = negCacheFacet.getClass().getMethod("invalidate");
            invalidateAllMethod.invoke(negCacheFacet);
          }
          catch (Exception ignored) {}
        }
      }
    }
    catch (Exception e) {
      log.debug("Negative cache invalidation skipped: {}", e.getMessage());
    }
  }

  private String extractRepoNameFromUrl(final String remoteUrl) {
    try {
      java.net.URL url = new java.net.URL(remoteUrl);
      String path = url.getPath();
      if (path.startsWith("/repository/")) {
        String repoPart = path.substring("/repository/".length());
        if (repoPart.endsWith("/")) repoPart = repoPart.substring(0, repoPart.length() - 1);
        int slashIdx = repoPart.indexOf('/');
        String repoName = (slashIdx > 0) ? repoPart.substring(0, slashIdx) : repoPart;
        if (!repoName.isEmpty()) return repoName;
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract repo name from URL {}: {}", remoteUrl, e.getMessage());
    }
    return null;
  }

  private String[] extractAuthFromRepo(final Repository repo) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) return null;
      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("httpclient")) return null;
      @SuppressWarnings("unchecked")
      Map<String, Object> httpClientAttrs = attributes.get("httpclient");
      @SuppressWarnings("unchecked")
      Map<String, Object> authAttrs = (Map<String, Object>) httpClientAttrs.get("authentication");
      if (authAttrs == null) return null;
      String username = (String) authAttrs.get("username");
      String password = (String) authAttrs.get("password");
      if (username != null && password != null) return new String[]{username, password};
    }
    catch (Exception e) {
      log.debug("Failed to extract auth from repo {}: {}", repo.getName(), e.getMessage());
    }
    return null;
  }

  private void setAuthHeaders(final HttpURLConnection conn, final String cookieHeader, final String csrfToken) {
    if (cookieHeader != null && !cookieHeader.isEmpty()) {
      conn.setRequestProperty("Cookie", cookieHeader);
    }
    if (csrfToken != null && !csrfToken.isEmpty()) {
      conn.setRequestProperty("NX-ANTI-CSRF-TOKEN", csrfToken);
    }
    conn.setRequestProperty("X-Nexus-UI", "true");
    conn.setRequestProperty("Accept", "*/*");
  }

  // ==================== JSON Parsing Helpers ====================

  private String extractJsonValue(final String json, final String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return null;
    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return null;
    int valueStart = json.indexOf('"', colonIdx + 1);
    if (valueStart < 0) return null;
    int valueEnd = valueStart + 1;
    while (valueEnd < json.length()) {
      char c = json.charAt(valueEnd);
      if (c == '"' && json.charAt(valueEnd - 1) != '\\') break;
      valueEnd++;
    }
    if (valueEnd >= json.length()) return null;
    return json.substring(valueStart + 1, valueEnd).replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private String extractJsonArray(final String json, final String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return null;
    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return null;
    int arrayStart = json.indexOf('[', colonIdx + 1);
    if (arrayStart < 0) return null;
    int arrayEnd = findMatchingBracket(json, arrayStart);
    if (arrayEnd < 0) return null;
    return json.substring(arrayStart + 1, arrayEnd);
  }

  private int findMatchingBracket(final String s, final int openPos) {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString) {
        if (c == '[') depth++;
        else if (c == ']') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  private int findMatchingBrace(final String s, final int openPos) {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString) {
        if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  // ==================== Utility ====================

  private String readStream(final InputStream input) throws IOException {
    StringBuilder sb = new StringBuilder();
    byte[] buffer = new byte[BUFFER_SIZE];
    int bytesRead;
    while ((bytesRead = input.read(buffer)) != -1) {
      sb.append(new String(buffer, 0, bytesRead, "UTF-8"));
    }
    return sb.toString();
  }

  private String readErrorResponse(final HttpURLConnection conn) throws IOException {
    InputStream errStream = conn.getErrorStream();
    if (errStream != null) {
      try { return readStream(errStream); } finally { try { errStream.close(); } catch (Exception e) { /* ignore */ } }
    }
    return "";
  }

  private String readResponse(final HttpURLConnection conn) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
      String line;
      while ((line = reader.readLine()) != null) { sb.append(line); }
    }
    conn.disconnect();
    return sb.toString();
  }

  private String encodeAuth(final String userPass) {
    return java.util.Base64.getEncoder().encodeToString(userPass.getBytes());
  }

  private String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }

  private PromotionTaskResult copyPromotionResult(final PromotionTaskResult src) {
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

  private void updatePromotionTaskProgress(final PromotionTaskResult taskResult,
                                            final List<PromotionTaskResult.FileItem> items) {
    if (taskResult != null) {
      taskResult.setItems(new ArrayList<>(items));
      String tid = taskResult.getTaskId();
      if (tid != null) {
        promotionTaskResults.put(tid, copyPromotionResult(taskResult));
      }
    }
  }
}
