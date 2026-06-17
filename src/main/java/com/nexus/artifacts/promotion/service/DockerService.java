package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
import com.nexus.artifacts.promotion.model.SyncRequest;
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

  /**
   * Trust-all-SSL manager for self-signed certificates.
   * Ensures HTTPS connections to remote storage with self-signed certs work correctly.
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
      log.info("DockerService SSL context initialized: trusting all certificates (supports self-signed HTTPS)");
    }
    catch (Exception e) {
      log.warn("Failed to initialize SSL trust manager for DockerService: {}", e.getMessage(), e);
    }
  }

  /** Maximum time (ms) to keep completed task results */
  private static final long TASK_RESULT_TTL_MS = 30 * 60 * 1000L;

  /** Connection/read timeout in milliseconds */
  private static final int TIMEOUT_MS = 300_000;

  /** Buffer size for streaming */
  private static final int BUFFER_SIZE = 8192;

  /** Maximum retry attempts for individual blob/manifest operations */
  private static final int DOCKER_RETRY_ATTEMPTS = 3;

  /** Nexus local base URLs for internal API calls - tries HTTPS first, then HTTP */
  private static final String LOCAL_NEXUS_BASE_HTTPS = "https://localhost:8081";
  private static final String LOCAL_NEXUS_BASE_HTTP = "http://localhost:8081";

  /** Cached working local base URL */
  private volatile String cachedLocalNexusBase = null;

  /**
   * Get the working local Nexus base URL.
   * Tries HTTPS first (for Nexus with SSL), then falls back to HTTP.
   * Result is cached after first successful connection.
   */
  private String getLocalNexusBase() {
    if (cachedLocalNexusBase != null) {
      return cachedLocalNexusBase;
    }

    // Try HTTPS first
    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTPS)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTPS;
      log.info("Local Nexus base URL resolved to HTTPS: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    // Fall back to HTTP
    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTP)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
      log.info("Local Nexus base URL resolved to HTTP: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    // Default to HTTP if both fail (Nexus might not be fully started yet)
    log.warn("Could not determine local Nexus base URL, defaulting to HTTP");
    cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
    return cachedLocalNexusBase;
  }

  /**
   * Test if a local Nexus URL is reachable.
   */
  private boolean testLocalConnection(final String baseUrl) {
    try {
      URL url = new URL(baseUrl + "/service/rest/v1/status");
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      int code = conn.getResponseCode();
      conn.disconnect();
      return code < 500;
    }
    catch (Exception e) {
      log.debug("Local connection test failed for {}: {}", baseUrl, e.getMessage());
      return false;
    }
  }

  /** Set of Docker host repository names configured as release repositories (for promotion) */
  private volatile Set<String> dockerReleaseRepos = new HashSet<>();

  /** Set of Docker proxy repository names configured as release repositories (for sync) */
  private volatile Set<String> dockerReleaseProxyRepos = new HashSet<>();

  /**
   * Patterns that indicate a development/snapshot tag (not a release tag).
   * Tags matching these patterns will be filtered out when promoting to a release repository.
   */
  private static final String[] SNAPSHOT_TAG_PATTERNS = {
      "SNAPSHOT", "snapshot",
      "-dev", "-DEV", "-Dev",
      "-alpha", "-ALPHA", "-Alpha",
      "-beta", "-BETA", "-Beta",
      "-rc", "-RC", "-Rc",
      "-pre", "-PRE", "-Pre",
      "-test", "-TEST", "-Test",
      "-canary", "-CANARY",
      "-nightly", "-NIGHTLY",
      "-latest", "-LATEST"
  };

  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;
  private final PermissionChecker permissionChecker;
  private final SecurityHelper securityHelper;
  private final FileWriteLockManager writeLockManager;
  private final HttpClientPool httpClientPool;

  private final Map<String, PromotionTaskResult> promotionTaskResults = new ConcurrentHashMap<>();
  private final Map<String, SyncTaskInfo> syncTaskInfos = new ConcurrentHashMap<>();

  @Inject
  public DockerService(final RepositoryManager repositoryManager,
                       final TaskExecutorService taskExecutor,
                       final TaskCacheManager cacheManager,
                       final PermissionChecker permissionChecker,
                       final SecurityHelper securityHelper,
                       final FileWriteLockManager writeLockManager,
                       final HttpClientPool httpClientPool)
  {
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
    this.permissionChecker = permissionChecker;
    this.securityHelper = securityHelper;
    this.writeLockManager = writeLockManager;
    this.httpClientPool = httpClientPool;
  }

  /**
   * Update Docker release repositories from capability configuration.
   * @param repos Comma-separated list of repository names
   */
  public void updateDockerReleaseRepos(final String repos) {
    Set<String> newRepos = new HashSet<>();
    if (repos != null && !repos.trim().isEmpty()) {
      String[] parts = repos.split(",");
      for (String part : parts) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) {
          newRepos.add(trimmed);
        }
      }
    }
    this.dockerReleaseRepos = newRepos;
    log.info("Docker release repositories updated: {}", newRepos);
  }

  /**
   * Check if a Docker repository is configured as a release repository (for promotion).
   */
  public boolean isDockerReleaseRepo(final String repoName) {
    return dockerReleaseRepos.contains(repoName);
  }

  /**
   * Update Docker release proxy repositories from capability configuration.
   * @param repos Comma-separated list of proxy repository names
   */
  public void updateDockerReleaseProxyRepos(final String repos) {
    Set<String> newRepos = new HashSet<>();
    if (repos != null && !repos.trim().isEmpty()) {
      String[] parts = repos.split(",");
      for (String part : parts) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) {
          newRepos.add(trimmed);
        }
      }
    }
    this.dockerReleaseProxyRepos = newRepos;
    log.info("Docker release proxy repositories updated: {}", newRepos);
  }

  /**
   * Check if a Docker proxy repository is configured as a release repository (for sync).
   */
  public boolean isDockerReleaseProxyRepo(final String repoName) {
    return dockerReleaseProxyRepos.contains(repoName);
  }

  /**
   * Check if a Docker tag is a release tag (not a snapshot/dev tag).
   * A tag is considered a release tag if it does NOT match any snapshot patterns.
   */
  public boolean isReleaseTag(final String tag) {
    if (tag == null || tag.isEmpty()) return false;
    for (String pattern : SNAPSHOT_TAG_PATTERNS) {
      if (tag.contains(pattern)) {
        return false;
      }
    }
    return true;
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

    // Strategy 1: Try internal StorageTx API first (most reliable, no HTTP auth issues)
    try {
      Map<String, DockerImageInfo> internalImages = listDockerImagesInternal(repo);
      if (!internalImages.isEmpty()) {
        response.setImages(new ArrayList<>(internalImages.values()));
        response.setTotalCount(internalImages.size());
        return response;
      }
    }
    catch (Exception e) {
      log.debug("Internal API listing failed for Docker images in {}, falling back: {}",
          repositoryName, e.getMessage());
    }

    // Strategy 2: Try local Nexus REST API
    try {
      Map<String, DockerImageInfo> localApiImages = listDockerImagesViaLocalApi(repositoryName);
      if (!localApiImages.isEmpty()) {
        response.setImages(new ArrayList<>(localApiImages.values()));
        response.setTotalCount(localApiImages.size());
        return response;
      }
    }
    catch (Exception e) {
      log.debug("Local REST API listing failed for Docker images in {}, falling back: {}",
          repositoryName, e.getMessage());
    }

    // Strategy 3: For proxy repos, try remote Nexus Search API
    if ("proxy".equals(repo.getType().getValue())) {
      try {
        Map<String, DockerImageInfo> remoteImages = listDockerImagesViaRemoteApi(repo);
        if (!remoteImages.isEmpty()) {
          response.setImages(new ArrayList<>(remoteImages.values()));
          response.setTotalCount(remoteImages.size());
          return response;
        }
      }
      catch (Exception e) {
        log.info("Remote Nexus API listing failed for Docker images in {}: {}", repositoryName, e.getMessage());
      }
    }

    response.setImages(new ArrayList<>(imageMap.values()));
    response.setTotalCount(imageMap.size());
    return response;
  }

  /**
   * List Docker images via local Nexus REST API (Components API).
   */
  private Map<String, DockerImageInfo> listDockerImagesViaLocalApi(final String repositoryName) {
    Map<String, DockerImageInfo> imageMap = new LinkedHashMap<>();
    try {
      String apiUrl = getLocalNexusBase() + "/service/rest/v1/components?repository=" + repositoryName;
      String continuationToken = null;
      String effectiveAuth = null;

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

        int code = conn.getResponseCode();
        if (code != 200) {
          log.info("Local Components API returned HTTP {} for {}", code, url);
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
      log.info("Local REST API listing failed for Docker images: {}", e.getMessage());
    }
    return imageMap;
  }

  /**
   * List Docker images from a remote Nexus instance via Search API.
   * This works for proxy repositories whose remote is another Nexus instance.
   */
  private Map<String, DockerImageInfo> listDockerImagesViaRemoteApi(final Repository repo) {
    Map<String, DockerImageInfo> imageMap = new LinkedHashMap<>();
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null || config.getAttributes() == null || !config.getAttributes().containsKey("proxy")) {
        return imageMap;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = config.getAttributes().get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return imageMap;

      // Extract base URL and repo name from remote URL
      int repoIdx = remoteUrl.indexOf("/repository/");
      if (repoIdx <= 0) return imageMap;

      String remoteBaseUrl = remoteUrl.substring(0, repoIdx);
      String repoPart = remoteUrl.substring(repoIdx + "/repository/".length());
      if (repoPart.endsWith("/")) repoPart = repoPart.substring(0, repoPart.length() - 1);
      int slashIdx = repoPart.indexOf('/');
      String remoteRepoName = (slashIdx > 0) ? repoPart.substring(0, slashIdx) : repoPart;

      String[] repoAuth = extractAuthFromRepo(repo);
      String effectiveAuth = (repoAuth != null && repoAuth.length >= 2 && repoAuth[0] != null)
          ? repoAuth[0] + ":" + repoAuth[1] : null;

      log.info("Trying remote Nexus API for Docker images: baseUrl={}, repo={}, auth={}",
          remoteBaseUrl, remoteRepoName, effectiveAuth != null ? "yes" : "none");

      // Use Search API with docker format filter
      String searchUrlBase = remoteBaseUrl + "/service/rest/v1/search/assets?repository=" + remoteRepoName
          + "&format=docker";
      String continuationToken = null;

      do {
        String searchUrl = searchUrlBase;
        if (continuationToken != null) {
          searchUrl += "&continuationToken=" + continuationToken;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(searchUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        log.info("Remote Nexus Search API response: HTTP {} for Docker images", code);

        if (code != 200) {
          conn.disconnect();
          // Try Components API as fallback
          return listDockerImagesViaRemoteComponentsApi(remoteBaseUrl, remoteRepoName, effectiveAuth);
        }

        String json = readResponse(conn);
        parseDockerSearchApiAssets(json, imageMap);
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.info("Remote Nexus Search API found {} Docker images", imageMap.size());

      // If Search API found nothing, try Components API
      if (imageMap.isEmpty()) {
        return listDockerImagesViaRemoteComponentsApi(remoteBaseUrl, remoteRepoName, effectiveAuth);
      }
    }
    catch (Exception e) {
      log.info("Remote Nexus API failed for Docker images: {}", e.getMessage());
    }
    return imageMap;
  }

  /**
   * List Docker images via remote Nexus Components API.
   */
  private Map<String, DockerImageInfo> listDockerImagesViaRemoteComponentsApi(
      final String remoteBaseUrl, final String remoteRepoName, final String effectiveAuth) {
    Map<String, DockerImageInfo> imageMap = new LinkedHashMap<>();
    try {
      String compUrlBase = remoteBaseUrl + "/service/rest/v1/components?repository=" + remoteRepoName;
      String continuationToken = null;

      do {
        String compUrl = compUrlBase;
        if (continuationToken != null) {
          compUrl += "&continuationToken=" + continuationToken;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(compUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        log.info("Remote Nexus Components API response: HTTP {} for Docker images", code);
        if (code != 200) {
          conn.disconnect();
          break;
        }

        String json = readResponse(conn);
        parseDockerComponents(json, imageMap);
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.info("Remote Nexus Components API found {} Docker images", imageMap.size());
    }
    catch (Exception e) {
      log.info("Remote Nexus Components API failed for Docker images: {}", e.getMessage());
    }
    return imageMap;
  }

  /**
   * Parse Docker images from Nexus Search API (assets) response.
   * Docker assets follow pattern: v2/<image>/manifests/<tag>
   */
  private void parseDockerSearchApiAssets(final String json, final Map<String, DockerImageInfo> imageMap) {
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return;

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String item = itemsSection.substring(objStart, objEnd + 1);
        String path = extractJsonValue(item, "path");

        if (path != null && path.contains("/manifests/")) {
          // Normalize path: strip leading slash
          String normalizedPath = path.startsWith("/") ? path.substring(1) : path;

          // Pattern: v2/<image>/manifests/<tag>
          if (normalizedPath.startsWith("v2/")) {
            int manifestsIdx = normalizedPath.indexOf("/manifests/");
            String imagePart = normalizedPath.substring(3, manifestsIdx);
            String tag = normalizedPath.substring(manifestsIdx + "/manifests/".length());

            if (!imagePart.isEmpty() && !tag.isEmpty() && !tag.endsWith("/")) {
              DockerImageInfo info = imageMap.get(imagePart);
              if (info == null) {
                info = new DockerImageInfo(imagePart);
                imageMap.put(imagePart, info);
              }
              info.addTag(tag);
            }
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.info("Failed to parse Docker Search API assets: {}", e.getMessage());
    }
  }

  /**
   * List tags for a specific Docker image.
   */
  public List<String> listDockerTags(final String repositoryName, final String imageName) {
    List<String> tags = new ArrayList<>();

    // For proxy repos, try remote APIs first to get accurate remote tag list
    try {
      Repository repo = repositoryManager.get(repositoryName);
      if (repo != null && "proxy".equals(repo.getType().getValue())) {
        // Strategy 1: Try Docker Registry V2 API (standard /v2/<image>/tags/list)
        try {
          List<String> registryTags = listDockerTagsViaRegistryApi(repo, imageName);
          if (!registryTags.isEmpty()) {
            log.info("Found {} tags for image {} via Docker Registry V2 API", registryTags.size(), imageName);
            return registryTags;
          }
        }
        catch (Exception e) {
          log.debug("Docker Registry V2 API listing failed for Docker tags {}/{}: {}",
              repositoryName, imageName, e.getMessage());
        }

        // Strategy 2: Try remote Nexus Search API
        try {
          List<String> remoteTags = listDockerTagsViaRemoteApi(repo, imageName);
          if (!remoteTags.isEmpty()) {
            return remoteTags;
          }
        }
        catch (Exception e) {
          log.debug("Remote API listing failed for Docker tags {}/{}: {}",
              repositoryName, imageName, e.getMessage());
        }
      }
    }
    catch (Exception e) {
      log.debug("Remote API listing failed for Docker tags {}/{}, falling back: {}",
          repositoryName, imageName, e.getMessage());
    }

    // Strategy 3: Try internal StorageTx API
    try {
      Repository repo = repositoryManager.get(repositoryName);
      if (repo != null) {
        List<String> internalTags = listDockerTagsInternal(repo, imageName);
        if (!internalTags.isEmpty()) {
          return internalTags;
        }
      }
    }
    catch (Exception e) {
      log.debug("Internal API listing failed for Docker tags {}/{}, falling back: {}",
          repositoryName, imageName, e.getMessage());
    }

    // Strategy 4: Try local Nexus REST API
    try {
      List<String> localTags = listDockerTagsViaLocalApi(repositoryName, imageName);
      if (!localTags.isEmpty()) {
        return localTags;
      }
    }
    catch (Exception e) {
      log.debug("Local REST API listing failed for Docker tags {}/{}, falling back: {}",
          repositoryName, imageName, e.getMessage());
    }

    return tags;
  }

  /**
   * List Docker tags via local Nexus REST API (Components API).
   */
  private List<String> listDockerTagsViaLocalApi(final String repositoryName, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
      String effectiveAuth = null;
      String apiUrl = getLocalNexusBase() + "/service/rest/v1/components?repository=" + repositoryName;
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
      log.info("Local REST API listing failed for Docker tags: {}", e.getMessage());
    }
    return tags;
  }

  /**
   * List Docker tags from a remote Nexus instance via Search API.
   */
  private List<String> listDockerTagsViaRemoteApi(final Repository repo, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null || config.getAttributes() == null || !config.getAttributes().containsKey("proxy")) {
        return tags;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = config.getAttributes().get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return tags;

      // Extract base URL and repo name from remote URL
      int repoIdx = remoteUrl.indexOf("/repository/");
      if (repoIdx <= 0) return tags;

      String remoteBaseUrl = remoteUrl.substring(0, repoIdx);
      String repoPart = remoteUrl.substring(repoIdx + "/repository/".length());
      if (repoPart.endsWith("/")) repoPart = repoPart.substring(0, repoPart.length() - 1);
      int slashIdx = repoPart.indexOf('/');
      String remoteRepoName = (slashIdx > 0) ? repoPart.substring(0, slashIdx) : repoPart;

      String[] repoAuth = extractAuthFromRepo(repo);
      String effectiveAuth = (repoAuth != null && repoAuth.length >= 2 && repoAuth[0] != null)
          ? repoAuth[0] + ":" + repoAuth[1] : null;

      // Try Docker Registry V2 API first (most direct)
      try {
        String tagsUrl = remoteUrl + (remoteUrl.endsWith("/") ? "" : "/") + "v2/" + imageName + "/tags/list";
        HttpURLConnection conn = (HttpURLConnection) new URL(tagsUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        if (code == 200) {
          String json = readResponse(conn);
          tags = parseDockerTagsList(json);
          if (!tags.isEmpty()) {
            log.info("Found {} tags for image {} via Docker Registry V2 API", tags.size(), imageName);
            return tags;
          }
        }
        conn.disconnect();
      }
      catch (Exception e) {
        log.debug("Docker Registry V2 API failed for {}: {}", imageName, e.getMessage());
      }

      // Try remote Nexus Search API
      String searchUrlBase = remoteBaseUrl + "/service/rest/v1/search/assets?repository=" + remoteRepoName
          + "&format=docker&group=" + java.net.URLEncoder.encode("v2/" + imageName, "UTF-8");
      String continuationToken = null;

      do {
        String searchUrl = searchUrlBase;
        if (continuationToken != null) {
          searchUrl += "&continuationToken=" + continuationToken;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(searchUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
          conn.disconnect();
          break;
        }

        String json = readResponse(conn);
        parseDockerTagsFromSearchApi(json, imageName, tags);
        continuationToken = extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.info("Remote Nexus API found {} tags for image {}", tags.size(), imageName);
    }
    catch (Exception e) {
      log.info("Remote API listing failed for Docker tags: {}", e.getMessage());
    }
    return tags;
  }

  /**
   * Parse Docker tags from Nexus Search API (assets) response for a specific image.
   */
  private void parseDockerTagsFromSearchApi(final String json, final String imageName, final List<String> tags) {
    try {
      String itemsSection = extractJsonArray(json, "items");
      if (itemsSection == null) return;

      String manifestPrefix = "v2/" + imageName + "/manifests/";

      int pos = 0;
      while (pos < itemsSection.length()) {
        int objStart = itemsSection.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(itemsSection, objStart);
        if (objEnd < 0) break;

        String item = itemsSection.substring(objStart, objEnd + 1);
        String path = extractJsonValue(item, "path");

        if (path != null) {
          String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
          if (normalizedPath.startsWith(manifestPrefix)) {
            String tag = normalizedPath.substring(manifestPrefix.length());
            if (!tag.isEmpty() && !tag.endsWith("/")) {
              if (!tags.contains(tag)) {
                tags.add(tag);
              }
            }
          }
        }

        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.info("Failed to parse Docker tags from Search API: {}", e.getMessage());
    }
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
          if (request.isAllImages()) {
            // Directory level: promote all images and all tags
            DockerImageListResponse imageList = listDockerImages(request.getSourceRepository());
            List<DockerImageInfo> images = imageList.getImages();
            log.info("Docker promotion (all images): found {} images in {}", images.size(), request.getSourceRepository());

            if (images.isEmpty()) {
              throw new IOException("No Docker images found in repository " + request.getSourceRepository());
            }

            for (DockerImageInfo img : images) {
              String imageName = img.getName();
              List<String> tags = img.getTags();
              if (tags == null || tags.isEmpty()) {
                tags = listDockerTags(request.getSourceRepository(), imageName);
              }

              // Filter tags for release repository
              if (isDockerReleaseRepo(request.getTargetRepository())) {
                List<String> originalTags = new ArrayList<>(tags);
                tags = new ArrayList<>();
                for (String tag : originalTags) {
                  if (isReleaseTag(tag)) {
                    tags.add(tag);
                  } else {
                    String manifestPath = "v2/" + imageName + "/manifests/" + tag;
                    PromotionTaskResult.FileItem skippedItem = new PromotionTaskResult.FileItem(manifestPath, "image");
                    skippedItem.setStatus("skipped");
                    skippedItem.setErrorMessage("Skipped: non-release tag (target is a release repository)");
                    promotedItems.add(skippedItem);
                  }
                }
              }

              for (String tag : tags) {
                try {
                  List<PromotionTaskResult.FileItem> tagItems = promoteDockerTag(
                      request.getSourceRepository(), request.getTargetRepository(),
                      imageName, tag, cookieHeader, csrfToken, nexusBaseUrl);
                  promotedItems.addAll(tagItems);
                }
                catch (Exception e) {
                  log.error("Failed to promote {}:{}: {}", imageName, tag, e.getMessage());
                  String manifestPath = "v2/" + imageName + "/manifests/" + tag;
                  PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(manifestPath, "image");
                  failedItem.setStatus("failed");
                  failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
                  promotedItems.add(failedItem);
                }
                updatePromotionTaskProgress(result, promotedItems);
              }
            }
          } else {
            // Single image mode
            // Determine which tags to promote
            List<String> tags = request.getTags();
            if (request.isAllTags()) {
              tags = listDockerTags(request.getSourceRepository(), request.getImage());
              log.info("Docker promotion: found {} tags for image {} in {}", tags.size(), request.getImage(), request.getSourceRepository());
            }

            // Filter tags if target repository is a release repository
            if (isDockerReleaseRepo(request.getTargetRepository())) {
              List<String> originalTags = new ArrayList<>(tags);
              tags = new ArrayList<>();
              for (String tag : originalTags) {
                if (isReleaseTag(tag)) {
                  tags.add(tag);
                }
                else {
                  log.info("Docker promotion: skipping non-release tag {} for release repository {}", tag, request.getTargetRepository());
                  String manifestPath = "v2/" + request.getImage() + "/manifests/" + tag;
                  PromotionTaskResult.FileItem skippedItem = new PromotionTaskResult.FileItem(manifestPath, "image");
                  skippedItem.setStatus("skipped");
                  skippedItem.setErrorMessage("Skipped: non-release tag (target is a release repository)");
                  promotedItems.add(skippedItem);
                }
              }
              log.info("Docker promotion: filtered {} tags to {} release tags for release repository {}",
                  originalTags.size(), tags.size(), request.getTargetRepository());
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

    // Use image:tag level lock to ensure Manifest + Blob atomicity for promotion
    try {
      writeLockManager.executeWithFileLockVoid(targetRepo, manifestPath, () -> {
        // Step 1: Download manifest from source (using connection pool)
        String manifestSourceUrl = nexusBaseUrl + "/repository/" + sourceRepo + "/" + manifestPath;
        Map<String, String> manifestHeaders = buildAuthHeaders(cookieHeader, csrfToken);
        HttpClientPool.HttpResponse manifestResponse = httpClientPool.getWithAccept(
            manifestSourceUrl,
            "application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json",
            manifestHeaders);

        int manifestResponseCode = manifestResponse.getStatusCode();
        if (manifestResponseCode != 200) {
          throw new RuntimeException(new IOException("Failed to download manifest " + manifestPath + ": HTTP " + manifestResponseCode + " - " + manifestResponse.getBody()));
        }

        String manifestContent = manifestResponse.getBody();
        String manifestDigest = manifestResponse.getDockerContentDigest();
        String manifestMediaType = manifestResponse.getContentType();

        // Step 2: Parse manifest using DockerManifestParser
        try {
          DockerManifestParser.DockerManifest parsed = DockerManifestParser.parse(manifestContent, manifestMediaType);

          if (parsed.isFatManifest()) {
            // Fat Manifest (multi-arch): promote each platform sub-manifest and its blobs
            log.info("[DOCKER-PROMO] {}:{} is a fat manifest with {} platform references",
                image, tag, parsed.getManifestReferences().size());

            for (DockerManifestParser.ManifestReference ref : parsed.getManifestReferences()) {
              String subManifestPath = "v2/" + image + "/manifests/" + ref.getDigest();
              try {
                // Download and promote the sub-manifest's blobs
                promoteSubManifestBlobs(sourceRepo, targetRepo, image, ref, cookieHeader, csrfToken, nexusBaseUrl, items);
              }
              catch (Exception e) {
                log.error("Failed to promote sub-manifest {} for {}:{}: {}", ref.getDigest(), image, tag, e.getMessage());
                PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(subManifestPath, "image");
                failedItem.setStatus("failed");
                failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
                items.add(failedItem);
              }
            }
          }
          else {
            // Single-platform manifest: promote config + layers
            promoteBlobsForParsedManifest(sourceRepo, targetRepo, image, parsed, cookieHeader, csrfToken, nexusBaseUrl, items);
          }
        }
        catch (Exception e) {
          log.warn("[DOCKER-PROMO] Failed to parse manifest for {}:{}, falling back to legacy parser: {}",
              image, tag, e.getMessage());
          // Fallback to legacy parser
          Set<String> blobDigests = parseManifestBlobs(manifestContent);
          log.info("[DOCKER-PROMO] Manifest for {}:{} references {} blobs (legacy)", image, tag, blobDigests.size());
          for (String digest : blobDigests) {
            String blobPath = "v2/" + image + "/blobs/" + digest;
            try {
              PromotionTaskResult.FileItem blobItem = promoteDockerBlobLocked(
                  sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl);
              items.add(blobItem);
            }
            catch (Exception ex) {
              log.error("Failed to promote blob {} for {}:{}: {}", digest, image, tag, ex.getMessage());
              PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(blobPath, "image");
              failedItem.setStatus("failed");
              failedItem.setErrorMessage(sanitizeErrorMessage(ex.getMessage()));
              items.add(failedItem);
            }
          }
        }

        // Step 3: Upload manifest to target
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
      });
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Docker tag promotion failed: " + e.getMessage(), e);
    }

    return items;
  }

  /**
   * Promote blobs for a single-platform parsed manifest.
   */
  private void promoteBlobsForParsedManifest(
      final String sourceRepo, final String targetRepo,
      final String image,
      final DockerManifestParser.DockerManifest parsed,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl,
      final List<PromotionTaskResult.FileItem> items) {

    Set<String> blobDigests = parsed.getAllBlobDigests();
    log.info("[DOCKER-PROMO] Promoting {} blobs for image {}", blobDigests.size(), image);

    for (String digest : blobDigests) {
      String blobPath = "v2/" + image + "/blobs/" + digest;
      try {
        PromotionTaskResult.FileItem blobItem = promoteDockerBlobLocked(
            sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl);
        items.add(blobItem);
      }
      catch (Exception e) {
        log.error("Failed to promote blob {} for image {}: {}", digest, image, e.getMessage());
        PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(blobPath, "image");
        failedItem.setStatus("failed");
        failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        items.add(failedItem);
      }
    }
  }

  /**
   * Promote blobs for a sub-manifest referenced by a fat manifest.
   * Downloads the sub-manifest from source, parses it, and promotes its blobs.
   */
  private void promoteSubManifestBlobs(
      final String sourceRepo, final String targetRepo,
      final String image,
      final DockerManifestParser.ManifestReference ref,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl,
      final List<PromotionTaskResult.FileItem> items) throws IOException {

    String subManifestPath = "v2/" + image + "/manifests/" + ref.getDigest();
    log.info("[DOCKER-PROMO] Promoting sub-manifest {} for platform {}", ref.getDigest(), ref);

    // Download sub-manifest from source (using connection pool)
    String subManifestUrl = nexusBaseUrl + "/repository/" + sourceRepo + "/" + subManifestPath;
    Map<String, String> subHeaders = buildAuthHeaders(cookieHeader, csrfToken);
    HttpClientPool.HttpResponse subResponse = httpClientPool.getWithAccept(
        subManifestUrl,
        DockerManifestParser.MEDIA_TYPE_DOCKER_V2 + ", "
            + DockerManifestParser.MEDIA_TYPE_OCI_MANIFEST + ", "
            + DockerManifestParser.MEDIA_TYPE_DOCKER_V1_SIGNED,
        subHeaders);

    int responseCode = subResponse.getStatusCode();
    if (responseCode != 200) {
      throw new IOException("Failed to download sub-manifest " + subManifestPath + ": HTTP " + responseCode + " - " + subResponse.getBody());
    }

    String subManifestContent = subResponse.getBody();
    String subMediaType = subResponse.getContentType();

    // Parse sub-manifest and promote its blobs
    try {
      DockerManifestParser.DockerManifest subParsed = DockerManifestParser.parse(subManifestContent, subMediaType);
      promoteBlobsForParsedManifest(sourceRepo, targetRepo, image, subParsed, cookieHeader, csrfToken, nexusBaseUrl, items);
    }
    catch (Exception e) {
      throw new IOException("Failed to parse sub-manifest " + ref.getDigest() + ": " + e.getMessage(), e);
    }

    // Upload sub-manifest to target
    try {
      PromotionTaskResult.FileItem subManifestItem = uploadDockerManifest(
          targetRepo, subManifestPath, subManifestContent, cookieHeader, csrfToken, nexusBaseUrl);
      items.add(subManifestItem);
    }
    catch (Exception e) {
      log.error("Failed to upload sub-manifest {} for image {}: {}", ref.getDigest(), image, e.getMessage());
      PromotionTaskResult.FileItem failedItem = new PromotionTaskResult.FileItem(subManifestPath, "image");
      failedItem.setStatus("failed");
      failedItem.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
      items.add(failedItem);
    }
  }

  /**
   * Promote a single Docker blob with MD5 incremental check (called within lock scope).
   * MD5 check and transfer are atomic under the image:tag lock.
   */
  private PromotionTaskResult.FileItem promoteDockerBlobLocked(
      final String sourceRepo, final String targetRepo,
      final String blobPath,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    // MD5 incremental check (already inside lock, TOCTOU-safe)
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

    // Perform blob transfer directly (already inside lock)
    return doPromoteBlobTransfer(sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5);
  }

  /**
   * Promote a single Docker blob with MD5 incremental check (standalone, acquires lock).
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

    // Use retry for blob transfer
    try {
      return RetryableOperation.execute("promote blob " + blobPath,
          () -> doPromoteBlobTransfer(sourceRepo, targetRepo, blobPath, cookieHeader, csrfToken, nexusBaseUrl, sourceMd5, targetMd5),
          DOCKER_RETRY_ATTEMPTS);
    }
    catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Blob promotion failed after retries: " + e.getMessage(), e);
    }
  }

  /**
   * Perform the actual blob transfer (download + upload) for Docker promotion.
   * Separated from promoteDockerBlob to enable retry.
   * Uses Transfer-Encoding: chunked for large blob streaming.
   */
  private PromotionTaskResult.FileItem doPromoteBlobTransfer(
      final String sourceRepo, final String targetRepo,
      final String blobPath,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl,
      final String sourceMd5, final String targetMd5) throws IOException
  {
    // Download from source using connection pool
    String sourceUrl = nexusBaseUrl + "/repository/" + sourceRepo + "/" + blobPath;
    Map<String, String> authHeaders = buildAuthHeaders(cookieHeader, csrfToken);

    HttpClientPool.HttpResponse downloadResponse = httpClientPool.get(sourceUrl, authHeaders);
    if (!downloadResponse.isSuccess()) {
      throw new IOException("Download blob failed: HTTP " + downloadResponse.getStatusCode() + " - " + downloadResponse.getBody());
    }

    // Upload to target using connection pool with streaming
    String targetUrl = nexusBaseUrl + "/repository/" + targetRepo + "/" + blobPath;
    byte[] blobData = downloadResponse.getBody().getBytes("UTF-8");

    HttpClientPool.HttpResponse uploadResponse = httpClientPool.put(
        targetUrl, blobData, "application/octet-stream", authHeaders);

    if (!uploadResponse.isSuccess()) {
      throw new IOException("Upload blob failed: HTTP " + uploadResponse.getStatusCode() + " - " + uploadResponse.getBody());
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
    try {
      return RetryableOperation.execute("upload manifest " + manifestPath,
          () -> doUploadManifest(targetRepo, manifestPath, manifestContent, cookieHeader, csrfToken, nexusBaseUrl),
          DOCKER_RETRY_ATTEMPTS);
    }
    catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw new IOException("Manifest upload failed after retries: " + e.getMessage(), e);
    }
  }

  /**
   * Perform the actual manifest upload. Separated for retry support.
   */
  private PromotionTaskResult.FileItem doUploadManifest(
      final String targetRepo, final String manifestPath,
      final String manifestContent,
      final String cookieHeader, final String csrfToken,
      final String nexusBaseUrl) throws IOException
  {
    String targetUrl = nexusBaseUrl + "/repository/" + targetRepo + "/" + manifestPath;
    Map<String, String> authHeaders = buildAuthHeaders(cookieHeader, csrfToken);

    HttpClientPool.HttpResponse response = httpClientPool.put(
        targetUrl, manifestContent.getBytes("UTF-8"),
        "application/vnd.docker.distribution.manifest.v2+json", authHeaders);

    if (!response.isSuccess()) {
      throw new IOException("Upload manifest failed: HTTP " + response.getStatusCode() + " - " + response.getBody());
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

  /**
   * Synchronous Docker proxy sync for scheduled tasks.
   * Uses asset-based approach: browses all Docker manifest assets, matches the sync path,
   * then syncs each matched image:tag combination.
   *
   * @param request the sync request containing repository name and optional path filter
   * @return SyncTaskInfo with sync results
   */
  public SyncTaskInfo syncDockerImageScheduled(final SyncRequest request) {
    String repoName = request.getRepositoryName();
    String syncPath = request.getPath();
    boolean isFullSync = (syncPath == null || syncPath.trim().isEmpty());

    Repository repo = repositoryManager.get(repoName);
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + repoName);
    }
    if (!"proxy".equals(repo.getType().getValue())) {
      throw new IllegalArgumentException("Repository is not a proxy type: " + repoName);
    }

    String taskId = "docker-scheduled-sync-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();

    SyncTaskInfo taskInfo = new SyncTaskInfo();
    taskInfo.setTaskId(taskId);
    taskInfo.setSourceRepository(repoName);
    taskInfo.setTargetRepository(repoName);
    taskInfo.setPath(isFullSync ? "" : syncPath);
    taskInfo.setDirectory(true);
    taskInfo.setFormat("docker");
    taskInfo.setUsername("system");
    taskInfo.setStartTime(System.currentTimeMillis());
    taskInfo.setStatus(TaskStatus.RUNNING);

    syncTaskInfos.put(taskId, taskInfo);

    try {
      // Step 1: Parse sync path to determine image and optional tag filter
      String imageFilter = null;
      String tagFilter = null;
      if (!isFullSync) {
        String normalizedPath = syncPath.trim();
        if (normalizedPath.startsWith("v2/")) {
          normalizedPath = normalizedPath.substring(3);
        }
        // Remove /manifests/<tag> suffix
        int manifestsIdx = normalizedPath.indexOf("/manifests/");
        if (manifestsIdx > 0) {
          imageFilter = normalizedPath.substring(0, manifestsIdx);
          tagFilter = normalizedPath.substring(manifestsIdx + "/manifests/".length());
        }
        else {
          int blobsIdx = normalizedPath.indexOf("/blobs/");
          if (blobsIdx > 0) {
            imageFilter = normalizedPath.substring(0, blobsIdx);
          }
          else {
            // Determine if path is a directory (image name) or a specific image:tag
            // by probing the remote API
            int lastSlash = normalizedPath.lastIndexOf('/');
            if (lastSlash > 0) {
              // Path like "cnp/8.3.2.925" - could be image=cnp tag=8.3.2.925,
              // or image=cnp/8.3.2.925 with all tags
              // Probe remote: first try treating full path as image name (directory)
              List<String> remoteTags = listDockerTagsRemote(repo, normalizedPath);
              if (!remoteTags.isEmpty()) {
                // Remote has tags for this image name -> it's a directory/image
                imageFilter = normalizedPath;
                log.info("Parsed Docker scheduled path '{}' as image='{}' (remote has {} tags), will sync all tags from remote",
                    normalizedPath, imageFilter, remoteTags.size());
              }
              else {
                // Remote doesn't have tags for full path -> treat last segment as tag
                imageFilter = normalizedPath.substring(0, lastSlash);
                tagFilter = normalizedPath.substring(lastSlash + 1);
                log.info("Parsed Docker scheduled path '{}' as image='{}', tag='{}' (remote has no tags for full path)",
                    normalizedPath, imageFilter, tagFilter);
              }
            }
            else {
              // No slash - just an image name, sync all tags
              imageFilter = normalizedPath;
              log.info("Parsed Docker scheduled path '{}' as image name, will sync all tags from remote", normalizedPath);
            }
          }
        }
      }

      // Step 2: Get image list using remote API first for sync operations
      Map<String, List<String>> imageTagsMap = new LinkedHashMap<>();

      if (imageFilter != null && tagFilter != null) {
        // Specific image:tag requested - just add it directly
        List<String> tags = new ArrayList<>();
        tags.add(tagFilter);
        imageTagsMap.put(imageFilter, tags);
        log.info("Docker scheduled sync: specific image:tag {}:{} in {}", imageFilter, tagFilter, repoName);
      }
      else if (imageFilter != null) {
        // Specific image requested - list tags using remote API first
        List<String> tags = listDockerTagsRemote(repo, imageFilter);
        if (!tags.isEmpty()) {
          imageTagsMap.put(imageFilter, tags);
        }
        log.info("Docker scheduled sync: found {} tags for image {} from remote {}", tags.size(), imageFilter, repoName);
      }
      else {
        // Full sync: list all images using remote API first
        DockerImageListResponse imageList = listDockerImages(repoName);
        List<DockerImageInfo> images = imageList.getImages();
        for (DockerImageInfo img : images) {
          // Always list tags from remote first for sync operations
          List<String> tags = listDockerTagsRemote(repo, img.getName());
          if (tags.isEmpty() && img.getTags() != null && !img.getTags().isEmpty()) {
            tags = img.getTags();
          }
          if (!tags.isEmpty()) {
            imageTagsMap.put(img.getName(), tags);
          }
        }
        log.info("Docker scheduled sync: found {} images in {} (full sync)", imageTagsMap.size(), repoName);
      }

      if (imageTagsMap.isEmpty()) {
        taskInfo.setStatus(TaskStatus.FAILED);
        taskInfo.setEndTime(System.currentTimeMillis());
        taskInfo.setErrorMessage("No Docker images or tags found in remote repository " + repoName);
        taskInfo.setResult("Failed: No Docker images or tags found in remote repository");
        syncTaskInfos.put(taskId, taskInfo);
        return taskInfo;
      }

      log.info("Docker scheduled sync: syncing {} images in {} (path filter: {})",
          imageTagsMap.size(), repoName, isFullSync ? "none" : syncPath);

      boolean isReleaseProxy = isDockerReleaseProxyRepo(repoName);
      List<SyncTaskInfo.SyncFileDetail> syncedFiles = new ArrayList<>();

      // Step 3: Sync each image:tag
      for (Map.Entry<String, List<String>> entry : imageTagsMap.entrySet()) {
        String imageName = entry.getKey();
        List<String> tags = entry.getValue();

        // Filter tags for release proxy repository
        if (isReleaseProxy) {
          List<String> originalTags = new ArrayList<>(tags);
          tags = new ArrayList<>();
          for (String tag : originalTags) {
            if (isReleaseTag(tag)) {
              tags.add(tag);
            } else {
              String manifestPath = "v2/" + imageName + "/manifests/" + tag;
              SyncTaskInfo.SyncFileDetail skippedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
              skippedDetail.setStatus("skipped");
              skippedDetail.setErrorMessage("Skipped: non-release tag (source is a release proxy repository)");
              syncedFiles.add(skippedDetail);
            }
          }
        }

        for (String tag : tags) {
          try {
            List<SyncTaskInfo.SyncFileDetail> tagFiles = syncDockerTag(repo, imageName, tag);
            syncedFiles.addAll(tagFiles);
          }
          catch (Exception e) {
            log.error("Failed to sync {}:{}: {}", imageName, tag, e.getMessage());
            String manifestPath = "v2/" + imageName + "/manifests/" + tag;
            SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
            failedDetail.setStatus("failed");
            failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
            syncedFiles.add(failedDetail);
          }
        }
      }

      taskInfo.setFileDetails(syncedFiles);
      taskInfo.setStatus(TaskStatus.COMPLETED);
      taskInfo.setEndTime(System.currentTimeMillis());

      long skippedCount = syncedFiles.stream().filter(f -> "skipped".equals(f.getStatus())).count();
      long failedCount = syncedFiles.stream().filter(f -> "failed".equals(f.getStatus())).count();
      long syncedCount = syncedFiles.size() - skippedCount - failedCount;
      taskInfo.setResult("Synced " + syncedCount + " items" +
          (skippedCount > 0 ? ", skipped " + skippedCount : "") +
          (failedCount > 0 ? ", failed " + failedCount : ""));

      log.info("Docker scheduled sync completed: {} synced, {} skipped, {} failed for {}",
          syncedCount, skippedCount, failedCount, repoName);
    }
    catch (Exception e) {
      log.error("Docker scheduled sync task failed: {}", e.getMessage(), e);
      taskInfo.setStatus(TaskStatus.FAILED);
      taskInfo.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
      taskInfo.setEndTime(System.currentTimeMillis());
      taskInfo.setResult("Failed: " + sanitizeErrorMessage(e.getMessage()));
    }

    syncTaskInfos.put(taskId, taskInfo);
    return taskInfo;
  }

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
        taskInfo.setPath(request.isAllImages() ? "v2/" : "v2/" + request.getImage());
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

          cacheManager.createTaskCache(preTaskId);

          List<SyncTaskInfo.SyncFileDetail> syncedFiles = new ArrayList<>();

          if (request.isAllImages()) {
            // Directory level: sync all images and all tags
            DockerImageListResponse imageList = listDockerImages(request.getSourceRepository());
            List<DockerImageInfo> images = imageList.getImages();
            log.info("Docker sync (all images): found {} images in {}", images.size(), request.getSourceRepository());

            if (images.isEmpty()) {
              throw new IOException("No Docker images found in remote repository " + request.getSourceRepository());
            }

            boolean isReleaseProxy = isDockerReleaseProxyRepo(request.getSourceRepository());

            for (DockerImageInfo img : images) {
              String imageName = img.getName();
              // Always list tags from remote first for sync operations
              List<String> tags = listDockerTagsRemote(repo, imageName);
              if (tags.isEmpty() && img.getTags() != null && !img.getTags().isEmpty()) {
                tags = img.getTags();
              }

              // Filter tags for release proxy repository
              if (isReleaseProxy) {
                List<String> originalTags = new ArrayList<>(tags);
                tags = new ArrayList<>();
                for (String tag : originalTags) {
                  if (isReleaseTag(tag)) {
                    tags.add(tag);
                  } else {
                    String manifestPath = "v2/" + imageName + "/manifests/" + tag;
                    SyncTaskInfo.SyncFileDetail skippedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
                    skippedDetail.setStatus("skipped");
                    skippedDetail.setErrorMessage("Skipped: non-release tag (source is a release proxy repository)");
                    syncedFiles.add(skippedDetail);
                  }
                }
              }

              for (String tag : tags) {
                try {
                  List<SyncTaskInfo.SyncFileDetail> tagFiles = syncDockerTag(repo, imageName, tag);
                  syncedFiles.addAll(tagFiles);
                }
                catch (Exception e) {
                  log.error("Failed to sync {}:{}: {}", imageName, tag, e.getMessage());
                  String manifestPath = "v2/" + imageName + "/manifests/" + tag;
                  SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
                  failedDetail.setStatus("failed");
                  failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
                  syncedFiles.add(failedDetail);
                }
              }
            }
          } else {
            // Single image mode
            // Determine which tags to sync
            List<String> tags = request.getTags();
            if (request.isAllTags()) {
              // List tags from remote first for sync operations - don't use local cache
              tags = listDockerTagsRemote(repo, request.getImage());
              log.info("Docker sync: found {} tags for image {} from remote {}", tags.size(), request.getImage(), request.getSourceRepository());
            }

            // Filter tags for release proxy repository
            if (isDockerReleaseProxyRepo(request.getSourceRepository())) {
              List<String> originalTags = new ArrayList<>(tags);
              tags = new ArrayList<>();
              for (String tag : originalTags) {
                if (isReleaseTag(tag)) {
                  tags.add(tag);
                } else {
                  String manifestPath = "v2/" + request.getImage() + "/manifests/" + tag;
                  SyncTaskInfo.SyncFileDetail skippedDetail = new SyncTaskInfo.SyncFileDetail(manifestPath, "image");
                  skippedDetail.setStatus("skipped");
                  skippedDetail.setErrorMessage("Skipped: non-release tag (source is a release proxy repository)");
                  syncedFiles.add(skippedDetail);
                }
              }
            }

            if (tags.isEmpty()) {
              throw new IOException("No tags found for image " + request.getImage() + " in remote repository " + request.getSourceRepository());
            }

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

    // Use image:tag level lock to ensure Manifest + Blob atomicity
    writeLockManager.executeWithFileLockVoid(repo.getName(), manifestPath, () -> {
      // Step 1: Sync manifest - delete cached, invalidate neg cache, dispatch
      SyncTaskInfo.SyncFileDetail manifestDetail = syncDockerAssetInternal(repo, manifestPath);
      details.add(manifestDetail);

      // Step 2: Read manifest content from local cache to parse blob references
      String manifestContent = readManifestContentFromCache(repo, manifestPath);

      if (manifestContent == null || manifestContent.isEmpty()) {
        log.warn("Could not read manifest content for {}:{}, cannot determine blob dependencies", image, tag);
        return;
      }

      // Step 3: Parse manifest using DockerManifestParser
      try {
        DockerManifestParser.DockerManifest parsed = DockerManifestParser.parse(manifestContent, null);

        if (parsed.isFatManifest()) {
          // Fat Manifest (multi-arch): sync each platform sub-manifest and its blobs
          log.info("[DOCKER-SYNC] {}:{} is a fat manifest with {} platform references",
              image, tag, parsed.getManifestReferences().size());

          for (DockerManifestParser.ManifestReference ref : parsed.getManifestReferences()) {
            String subManifestPath = "v2/" + image + "/manifests/" + ref.getDigest();
            try {
              // Sync the sub-manifest
              SyncTaskInfo.SyncFileDetail subDetail = syncDockerAssetInternal(repo, subManifestPath);
              details.add(subDetail);

              // Read and parse the sub-manifest to get its blobs
              String subManifestContent = readManifestContentFromCache(repo, subManifestPath);
              if (subManifestContent != null && !subManifestContent.isEmpty()) {
                DockerManifestParser.DockerManifest subParsed = DockerManifestParser.parse(subManifestContent, ref.getMediaType());
                syncBlobsForManifest(repo, image, subParsed, details);
              }
            }
            catch (Exception e) {
              log.error("Failed to sync sub-manifest {} for {}:{}: {}", ref.getDigest(), image, tag, e.getMessage());
              SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(subManifestPath, "image");
              failedDetail.setStatus("failed");
              failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
              details.add(failedDetail);
            }
          }
        }
        else {
          // Single-platform manifest: sync config + layers
          syncBlobsForManifest(repo, image, parsed, details);
        }
      }
      catch (Exception e) {
        log.warn("[DOCKER-SYNC] Failed to parse manifest for {}:{}, falling back to legacy parser: {}",
            image, tag, e.getMessage());
        // Fallback to legacy parser
        Set<String> blobDigests = parseManifestBlobs(manifestContent);
        log.info("[DOCKER-SYNC] Manifest for {}:{} references {} blobs (legacy)", image, tag, blobDigests.size());
        for (String digest : blobDigests) {
          String blobPath = "v2/" + image + "/blobs/" + digest;
          try {
            SyncTaskInfo.SyncFileDetail blobDetail = syncDockerAssetInternal(repo, blobPath);
            details.add(blobDetail);
          }
          catch (Exception ex) {
            log.error("Failed to sync blob {} for {}:{}: {}", digest, image, tag, ex.getMessage());
            SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(blobPath, "image");
            failedDetail.setStatus("failed");
            failedDetail.setErrorMessage(sanitizeErrorMessage(ex.getMessage()));
            details.add(failedDetail);
          }
        }
      }
    });

    return details;
  }

  /**
   * Sync all blobs (config + layers) for a single-platform manifest.
   */
  private void syncBlobsForManifest(final Repository repo, final String image,
                                     final DockerManifestParser.DockerManifest manifest,
                                     final List<SyncTaskInfo.SyncFileDetail> details) {
    Set<String> blobDigests = manifest.getAllBlobDigests();
    log.info("[DOCKER-SYNC] Syncing {} blobs for image {}", blobDigests.size(), image);

    for (String digest : blobDigests) {
      String blobPath = "v2/" + image + "/blobs/" + digest;
      try {
        SyncTaskInfo.SyncFileDetail blobDetail = syncDockerAssetInternal(repo, blobPath);
        details.add(blobDetail);
      }
      catch (Exception e) {
        log.error("Failed to sync blob {} for image {}: {}", digest, image, e.getMessage());
        SyncTaskInfo.SyncFileDetail failedDetail = new SyncTaskInfo.SyncFileDetail(blobPath, "image");
        failedDetail.setStatus("failed");
        failedDetail.setErrorMessage(sanitizeErrorMessage(e.getMessage()));
        details.add(failedDetail);
      }
    }
  }

  /**
   * Internal method to sync a single Docker asset without acquiring lock (called within lock scope).
   */
  private SyncTaskInfo.SyncFileDetail syncDockerAssetInternal(final Repository repo, final String assetPath) throws Exception {
    SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(assetPath, "image");

    // Always re-sync: delete cached asset + invalidate negative cache + dispatch GET
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
   * Tries Docker Registry V2 API first, then remote Nexus Search API.
   * Does NOT fall back to local cache - only returns tags actually available on remote.
   */
  private List<String> listDockerTagsRemote(final Repository repo, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
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
          else {
            log.info("Docker Registry API returned HTTP {} for image {}, trying remote Nexus API", code, imageName);
          }
          conn.disconnect();

          // Try remote Nexus Search API
          List<String> remoteTags = listDockerTagsViaRemoteApi(repo, imageName);
          if (!remoteTags.isEmpty()) {
            log.info("Found {} tags for image {} via remote Nexus API", remoteTags.size(), imageName);
            return remoteTags;
          }
        }
      }
    }
    catch (Exception e) {
      log.debug("Docker Registry V2 API failed for {}: {}", imageName, e.getMessage());
    }

    // Return empty list - do not fall back to local cache for sync operations
    log.info("No tags found for image {} from remote, returning empty list (not using local cache)", imageName);
    return tags;
  }

  /**
   * List Docker tags via Docker Registry V2 API (/v2/<image>/tags/list).
   * This is the standard Docker distribution API and works with any Docker v2 compatible registry.
   */
  private List<String> listDockerTagsViaRegistryApi(final Repository repo, final String imageName) {
    List<String> tags = new ArrayList<>();
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null || config.getAttributes() == null || !config.getAttributes().containsKey("proxy")) {
        return tags;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = config.getAttributes().get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        return tags;
      }

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

      try {
        int code = conn.getResponseCode();
        if (code == 200) {
          String json = readResponse(conn);
          tags = parseDockerTagsList(json);
          log.info("Docker Registry V2 API found {} tags for image {}", tags.size(), imageName);
        }
        else {
          log.debug("Docker Registry V2 API returned HTTP {} for image {}", code, imageName);
        }
      }
      finally {
        conn.disconnect();
      }
    }
    catch (Exception e) {
      log.debug("Docker Registry V2 API failed for {}: {}", imageName, e.getMessage());
    }
    return tags;
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
      if ("completed".equalsIgnoreCase(statusStr) || "failed".equalsIgnoreCase(statusStr)) {
        taskExecutor.cleanupPromotionTaskHandle(taskId);
      }
    }
    return result;
  }

  public SyncTaskInfo getSyncTaskInfo(final String taskId) {
    cleanupExpiredSyncTaskInfos();
    SyncTaskInfo info = syncTaskInfos.get(taskId);
    if (info != null) {
      // Only override status from TaskExecutor if info is not already in a terminal state
      // (DockerService sets FAILED/COMPLETED internally before TaskExecutor's finally block)
      if (info.getStatus() != TaskStatus.FAILED && info.getStatus() != TaskStatus.COMPLETED) {
        TaskStatus status = taskExecutor.getSyncTaskStatus(taskId);
        if (status != null) {
          info.setStatus(status);
        }
      }
      if (info.getStatus() == TaskStatus.COMPLETED || info.getStatus() == TaskStatus.FAILED) {
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
      if (("completed".equalsIgnoreCase(s) || "failed".equalsIgnoreCase(s)) && r.getEndTime() > 0
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

  /**
   * Get all Docker sync task infos (for queue display).
   * Called by SyncService to merge Docker sync tasks into the unified queue view.
   */
  public List<SyncTaskInfo> getAllSyncTaskInfos() {
    cleanupExpiredSyncTaskInfos();
    List<SyncTaskInfo> tasks = new ArrayList<>();
    for (Map.Entry<String, SyncTaskInfo> entry : syncTaskInfos.entrySet()) {
      SyncTaskInfo info = entry.getValue();
      TaskStatus status = taskExecutor.getSyncTaskStatus(entry.getKey());
      if (status != null) {
        info.setStatus(status);
      }
      if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
        taskExecutor.cleanupSyncTaskHandle(entry.getKey());
      }
      tasks.add(info);
    }
    return tasks;
  }

  /**
   * List Docker tags for a specific image using Nexus internal StorageTx API.
   * This is the most reliable approach as it bypasses HTTP authentication issues.
   */
  private List<String> listDockerTagsInternal(final Repository repo, final String imageName) {
    List<String> tags = new ArrayList<>();
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) {
        return tags;
      }

      // Docker manifest assets follow pattern: v2/<image>/manifests/<tag>
      String manifestPrefix = "v2/" + imageName + "/manifests/";

      Iterable<Asset> assets = tx.browseAssets(bucket);
      for (Asset asset : assets) {
        String name = asset.name();
        if (name == null) continue;

        // Normalize
        String normalizedName = name.startsWith("/") ? name.substring(1) : name;

        if (normalizedName.startsWith(manifestPrefix)) {
          String tag = normalizedName.substring(manifestPrefix.length());
          if (!tag.isEmpty() && !tag.endsWith("/")) {
            tags.add(tag);
          }
        }
      }

      log.debug("Internal API found {} tags for image {} in repo {}", tags.size(), imageName, repo.getName());
    }
    catch (Exception e) {
      log.debug("Internal API listing failed for Docker tags {}/{}: {}", repo.getName(), imageName, e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return tags;
  }

  // ==================== Internal API Listing ====================

  /**
   * List Docker images using Nexus internal StorageTx API.
   * This is the most reliable approach as it bypasses HTTP authentication issues.
   * Works for all repository types including proxy repositories.
   */
  private Map<String, DockerImageInfo> listDockerImagesInternal(final Repository repo) {
    Map<String, DockerImageInfo> imageMap = new LinkedHashMap<>();
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) {
        return imageMap;
      }

      // Docker assets follow pattern: v2/<image>/manifests/<tag>
      // and v2/<image>/blobs/<digest>
      Iterable<Asset> assets = tx.browseAssets(bucket);
      for (Asset asset : assets) {
        String name = asset.name();
        if (name == null) continue;

        // Match manifest assets to extract image and tag
        // Pattern: /v2/<image>/manifests/<tag>
        if (name.contains("/manifests/")) {
          int manifestsIdx = name.indexOf("/manifests/");
          String prefix = name.substring(0, manifestsIdx);
          // Strip leading slash and v2/
          if (prefix.startsWith("/")) prefix = prefix.substring(1);
          if (prefix.startsWith("v2/")) prefix = prefix.substring(3);
          String imageName = prefix;
          String tag = name.substring(manifestsIdx + "/manifests/".length());
          if (tag.startsWith("/")) tag = tag.substring(1);

          if (!imageName.isEmpty() && !tag.isEmpty()) {
            DockerImageInfo info = imageMap.get(imageName);
            if (info == null) {
              info = new DockerImageInfo();
              info.setName(imageName);
              info.setTags(new ArrayList<>());
              imageMap.put(imageName, info);
            }
            if (!info.getTags().contains(tag)) {
              info.getTags().add(tag);
            }
          }
        }
      }

      log.debug("Internal API found {} Docker images in repo {}", imageMap.size(), repo.getName());
    }
    catch (Exception e) {
      log.debug("Internal API listing failed for Docker images in {}: {}", repo.getName(), e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return imageMap;
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

  /**
   * Build auth headers map for HttpClientPool requests.
   */
  private Map<String, String> buildAuthHeaders(final String cookieHeader, final String csrfToken) {
    Map<String, String> headers = new java.util.HashMap<>();
    if (cookieHeader != null && !cookieHeader.isEmpty()) {
      headers.put("Cookie", cookieHeader);
    }
    if (csrfToken != null && !csrfToken.isEmpty()) {
      headers.put("NX-ANTI-CSRF-TOKEN", csrfToken);
    }
    headers.put("X-Nexus-UI", "true");
    headers.put("Accept", "*/*");
    return headers;
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

  private String extractChecksumValue(final String json, final String algo) {
    String checksumsPattern = "\"checksums\"";
    int checksumsIdx = json.indexOf(checksumsPattern);
    if (checksumsIdx < 0) return null;

    int objStart = json.indexOf('{', checksumsIdx + checksumsPattern.length());
    if (objStart < 0) return null;

    int objEnd = findMatchingBrace(json, objStart);
    if (objEnd < 0) return null;

    String checksumsObj = json.substring(objStart, objEnd + 1);
    return extractJsonValue(checksumsObj, algo);
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
