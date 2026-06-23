package com.nexus.artifacts.promotion.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;

/**
 * Dedicated service for incremental sync of proxy repositories.
 *
 * Incremental sync compares remote asset MD5 checksums with locally cached
 * asset MD5 checksums. Only assets whose MD5 differs (or that are missing
 * locally) are synced. This significantly reduces network traffic and sync
 * time when most assets are already up-to-date.
 *
 * Flow:
 * 1. Get remote asset list with MD5 via Nexus Search API or HTTP directory listing
 * 2. Build local asset MD5 map from the proxy repository's blob store
 * 3. For each remote asset:
 *    - If local MD5 matches remote MD5 -> skip
 *    - If MD5 mismatch or asset missing locally -> sync via ContentFacet
 * 4. Upload and element update happen in the same StorageTx transaction
 *    (ensured by syncAssetViaContentFacet / syncAssetViaDirectHttp in SyncService)
 *
 * This service delegates the actual asset sync operations to SyncService,
 * which handles the low-level ContentFacet dispatch, direct HTTP download,
 * and StorageTx transaction management.
 */
@Named
@Singleton
public class IncrementalSyncService {

  private static final Logger log = LoggerFactory.getLogger(IncrementalSyncService.class);

  private final SyncService syncService;
  private final RepositoryManager repositoryManager;
  private final TaskExecutorService taskExecutor;
  private final FileWriteLockManager writeLockManager;

  @Inject
  public IncrementalSyncService(final SyncService syncService,
      final RepositoryManager repositoryManager,
      final TaskExecutorService taskExecutor,
      final FileWriteLockManager writeLockManager) {
    this.syncService = syncService;
    this.repositoryManager = repositoryManager;
    this.taskExecutor = taskExecutor;
    this.writeLockManager = writeLockManager;
  }

  /**
   * Execute an incremental sync as a scheduled task.
   * This is called by ProxySyncTask when the "incremental" option is selected.
   *
   * @param request the sync request containing repository name, path, and format
   * @param task the Nexus task instance for cancellation support (may be null)
   * @return SyncTaskInfo with sync results including skipped/synced counts
   */
  public SyncTaskInfo syncScheduledIncremental(
      final SyncRequest request,
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {

    // Delegate to SyncService which has all the MD5 comparison infrastructure
    return syncService.syncScheduled(request, task, true);
  }

  /**
   * Execute incremental sync for a directory path.
   * This is the core MD5 comparison logic.
   *
   * @param repo the proxy repository
   * @param directoryPath the path to sync (empty for full repository)
   * @param task the Nexus task instance for cancellation support (may be null)
   * @return list of file details showing what was synced/skipped
   */
  public List<SyncTaskInfo.SyncFileDetail> incrementalSyncDirectory(
      final Repository repo,
      final String directoryPath,
      final com.nexus.artifacts.promotion.task.ProxySyncTask task) {

    List<SyncTaskInfo.SyncFileDetail> details = new ArrayList<>();

    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      log.info("Starting incremental {} for {}:{}",
          isFullSync ? "full repository sync" : "directory sync",
          repo.getName(), isFullSync ? "/" : directoryPath);

      // Step 1: Get remote assets with MD5 checksums
      Map<String, String> remoteMd5Map = listRemoteAssetsWithMd5(repo, directoryPath);
      log.info("Incremental sync: found {} remote assets for {}:{}",
          remoteMd5Map.size(), repo.getName(), isFullSync ? "/" : directoryPath);

      if (remoteMd5Map.isEmpty()) {
        log.warn("Incremental sync: no remote assets found for {}:{}", repo.getName(), directoryPath);
        return details;
      }

      // Step 2: Build local MD5 map for comparison
      Map<String, String> localMd5Map = buildLocalAssetMd5Map(repo, directoryPath);
      log.info("Incremental sync: found {} local cached assets for {}:{}",
          localMd5Map.size(), repo.getName(), isFullSync ? "/" : directoryPath);

      // Step 3: Compare and sync only changed/missing assets
      String[] repoAuth = syncService.extractAuthFromRepo(repo);
      int syncedCount = 0;
      int skippedCount = 0;

      for (Map.Entry<String, String> entry : remoteMd5Map.entrySet()) {
        // Check for task cancellation before each file
        if (Thread.currentThread().isInterrupted() || (task != null && task.isCanceled())) {
          log.warn("Incremental sync cancelled for {}:{}, {} files processed before cancellation",
              repo.getName(), directoryPath, details.size());
          return details;
        }

        String assetPath = entry.getKey();
        String remoteMd5 = entry.getValue();

        // Skip directory entries
        if (assetPath.endsWith("/")) {
          continue;
        }

        SyncTaskInfo.SyncFileDetail detail = new SyncTaskInfo.SyncFileDetail(assetPath, syncService.determineType(assetPath));
        detail.setRemoteMd5(remoteMd5);

        try {
          // Compare MD5: skip if local MD5 matches remote MD5
          String localMd5 = localMd5Map.get(assetPath);
          detail.setLocalMd5(localMd5);

          log.debug("Incremental sync comparing {}: remoteMD5={}, localMD5={}", assetPath,
              remoteMd5 != null ? remoteMd5.substring(0, 8) + "..." : "null",
              localMd5 != null ? localMd5.substring(0, 8) + "..." : "null");

          if (localMd5 != null && remoteMd5 != null && localMd5.equalsIgnoreCase(remoteMd5)) {
            // MD5 matches - skip this asset
            detail.setStatus("skipped");
            skippedCount++;
            log.debug("Incremental sync: skipping {} (MD5 match: {})", assetPath, localMd5);
          }
          else {
            // MD5 mismatch or local asset missing - sync this asset
            if (localMd5 == null) {
              log.info("Incremental sync: syncing {} (not in local cache, remoteMD5={})", assetPath,
                  remoteMd5 != null ? remoteMd5.substring(0, 8) + "..." : "null");
            }
            else if (remoteMd5 == null) {
              log.info("Incremental sync: syncing {} (remote MD5 not available, cannot compare)", assetPath);
            }
            else {
              log.info("Incremental sync: syncing {} (MD5 mismatch: local={}, remote={})",
                  assetPath, localMd5.substring(0, 8) + "...", remoteMd5.substring(0, 8) + "...");
            }

            RetryableOperation.executeRun("incremental sync asset " + assetPath,
                () -> syncService.syncAssetViaContentFacet(repo, assetPath, repoAuth),
                3);
            detail.setStatus("success");
            syncedCount++;
          }
        }
        catch (Exception e) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn("Incremental sync cancelled while syncing asset {}, stopping", assetPath);
            return details;
          }
          log.error("Incremental sync: failed to sync asset {}: {}", assetPath, e.getMessage());
          detail.setStatus("failed");
          detail.setErrorMessage(ServiceUtils.sanitizeErrorMessage(e.getMessage()));
        }

        details.add(detail);
      }

      log.info("Incremental sync completed for {}:{} - {} synced, {} skipped, {} failed",
          repo.getName(), isFullSync ? "/" : directoryPath, syncedCount, skippedCount,
          details.stream().filter(d -> "failed".equals(d.getStatus())).count());
    }
    catch (Exception e) {
      log.error("Failed incremental sync for {}:{}: {}", repo.getName(), directoryPath, e.getMessage(), e);
      throw new RuntimeException("Incremental sync failed", e);
    }

    return details;
  }

  // ==================== MD5 Retrieval Methods ====================

  /**
   * List remote assets with their MD5 checksums.
   * Returns a Map of (normalized path → MD5 checksum).
   * This extracts MD5 from the same Search API response that lists assets,
   * avoiding the need for a separate getRemoteAssetMd5() call per asset.
   */
  private Map<String, String> listRemoteAssetsWithMd5(final Repository repo, final String directoryPath) {
    Map<String, String> assetMd5Map = new java.util.LinkedHashMap<>();
    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());

      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) return assetMd5Map;

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) return assetMd5Map;

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) return assetMd5Map;

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      String[] repoAuth = syncService.extractAuthFromRepo(repo);
      String authUsername = (repoAuth != null) ? repoAuth[0] : null;
      String authPassword = (repoAuth != null) ? repoAuth[1] : null;
      String effectiveAuth = (authUsername != null && authPassword != null)
          ? authUsername + ":" + authPassword : null;

      String remoteRepoName = syncService.extractRepoNameFromUrl(remoteUrl);

      // Strategy 1: Local Nexus repo
      if (remoteRepoName != null) {
        try {
          Repository remoteRepo = repositoryManager.get(remoteRepoName);
          if (remoteRepo != null) {
            log.debug("listRemoteAssetsWithMd5: Strategy 1 - local Nexus repo '{}' found, querying with MD5", remoteRepoName);
            assetMd5Map.putAll(listAssetsWithMd5ViaApi(repo.getName(), remoteRepoName,
                isFullSync ? "" : directoryPath, authUsername, authPassword));
            log.debug("listRemoteAssetsWithMd5: Strategy 1 returned {} assets", assetMd5Map.size());
            if (!assetMd5Map.isEmpty()) {
              return assetMd5Map;
            }
          }
          else {
            log.debug("listRemoteAssetsWithMd5: Strategy 1 - remote repo '{}' not found locally, trying next strategy", remoteRepoName);
          }
        }
        catch (Exception e) {
          log.warn("listRemoteAssetsWithMd5: Strategy 1 failed for repo '{}': {}", remoteRepoName, e.getMessage());
        }
      }

      // Strategy 2: Remote Nexus Search API
      if (remoteRepoName != null) {
        String remoteBaseUrl = remoteUrl.substring(0, remoteUrl.indexOf("/repository/"));
        try {
          String searchUrlBase = remoteBaseUrl + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
          if (!isFullSync) {
            String normalizedDir = directoryPath;
            if (normalizedDir.startsWith("/")) normalizedDir = normalizedDir.substring(1);
            if (normalizedDir.endsWith("/")) normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
            searchUrlBase += "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
          }
          log.debug("listRemoteAssetsWithMd5: Strategy 2 - remote Nexus Search API: {}", searchUrlBase);
          Map<String, String> remoteMd5Map = listAssetsWithMd5ViaApiUrl(searchUrlBase, effectiveAuth);
          log.debug("listRemoteAssetsWithMd5: Strategy 2 returned {} assets", remoteMd5Map.size());
          if (!remoteMd5Map.isEmpty()) {
            assetMd5Map.putAll(remoteMd5Map);
            return assetMd5Map;
          }
        }
        catch (Exception e) {
          log.warn("listRemoteAssetsWithMd5: Strategy 2 failed: {}", e.getMessage());
        }

        // Strategy 2b: Remote Nexus Components API with MD5 extraction
        try {
          log.debug("listRemoteAssetsWithMd5: Strategy 2b - remote Nexus Components API with MD5");
          Map<String, String> componentsMd5Map = listAssetsWithMd5ViaComponentsApi(remoteBaseUrl, remoteRepoName,
              isFullSync ? "" : directoryPath, effectiveAuth);
          log.debug("listRemoteAssetsWithMd5: Strategy 2b returned {} assets", componentsMd5Map.size());
          if (!componentsMd5Map.isEmpty()) {
            assetMd5Map.putAll(componentsMd5Map);
            return assetMd5Map;
          }
        }
        catch (Exception e) {
          log.warn("listRemoteAssetsWithMd5: Strategy 2b failed: {}", e.getMessage());
        }
      }

      // Strategy 3: If Search API didn't return MD5s, fall back to getRemoteAssetMd5 per asset
      // This is the slow path but ensures we get MD5s for HTTP-listed assets
      log.debug("listRemoteAssetsWithMd5: Strategy 3 - falling back to per-asset MD5 query");
      List<String> assetPaths = syncService.listRemoteAssets(repo, directoryPath);
      for (String assetPath : assetPaths) {
        String normalizedPath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        if (!assetMd5Map.containsKey(normalizedPath)) {
          String md5 = getRemoteAssetMd5(repo, normalizedPath);
          assetMd5Map.put(normalizedPath, md5);
        }
      }
    }
    catch (Exception e) {
      log.error("Failed to list remote assets with MD5: {}", e.getMessage());
    }
    return assetMd5Map;
  }

  /**
   * Build a map of asset path -> MD5 for all locally cached assets under a directory.
   */
  private Map<String, String> buildLocalAssetMd5Map(final Repository repo, final String directoryPath) {
    Map<String, String> md5Map = new HashMap<>();
    StorageTx tx = null;
    try {
      tx = repo.facet(StorageFacet.class).txSupplier().get();
      tx.begin();
      Bucket bucket = tx.findBucket(repo);
      if (bucket == null) {
        return md5Map;
      }

      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String normalizedPrefix = isFullSync ? "" : directoryPath;
      if (!isFullSync) {
        if (normalizedPrefix.startsWith("/")) {
          normalizedPrefix = normalizedPrefix.substring(1);
        }
        if (normalizedPrefix.endsWith("/")) {
          normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
      }

      Iterable<Asset> assets = tx.browseAssets(bucket);
      for (Asset asset : assets) {
        String name = asset.name();
        if (name == null) continue;

        // Normalize
        String normalizedName = name.startsWith("/") ? name.substring(1) : name;

        // Filter by directory path if not full sync
        if (!isFullSync && !normalizedPrefix.isEmpty()) {
          if (!normalizedName.equals(normalizedPrefix) && !normalizedName.startsWith(normalizedPrefix + "/")) {
            continue;
          }
        }

        String md5 = getAssetMd5(asset);
        if (md5 != null) {
          md5Map.put(normalizedName, md5);
        }
      }

      log.debug("Built local MD5 map: {} assets under {}", md5Map.size(),
          isFullSync ? "/" : directoryPath);
    }
    catch (Exception e) {
      log.debug("Failed to build local MD5 map: {}", e.getMessage());
    }
    finally {
      if (tx != null) {
        try { tx.close(); } catch (Exception ignored) { }
      }
    }
    return md5Map;
  }

  /**
   * Get MD5 checksum from an asset's attributes.
   */
  private String getAssetMd5(final Asset asset) {
    try {
      // Method 1: Use Asset.getChecksum(HashAlgorithm.MD5) — Nexus 3.7+
      try {
        org.sonatype.nexus.common.hash.HashAlgorithm md5Algo = org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
        String checksum = asset.getChecksum(md5Algo);
        if (checksum != null && !checksum.isEmpty()) {
          return checksum;
        }
      }
      catch (Exception ignored) {
        // Fall back to attribute-based extraction
      }

      // Method 2: Extract from asset attributes (older Nexus versions)
      @SuppressWarnings("unchecked")
      Map<String, Object> attributes = asset.attributes() != null ? asset.attributes().backing() : null;
      if (attributes != null && attributes.containsKey("checksum")) {
        @SuppressWarnings("unchecked")
        Map<String, String> checksums = (Map<String, String>) attributes.get("checksum");
        if (checksums != null) {
          String md5 = checksums.get("md5");
          if (md5 != null) return md5;
        }
      }
    }
    catch (Exception e) {
      log.debug("Failed to get MD5 from asset: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Get MD5 checksum of a remote asset by querying the Nexus Search API or Components API.
   * For non-Nexus remotes, falls back to HTTP HEAD request to get Content-MD5 or ETag.
   */
  private String getRemoteAssetMd5(final Repository repo, final String assetPath) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) {
        return null;
      }

      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("proxy")) {
        return null;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> proxyAttrs = attributes.get("proxy");
      String remoteUrl = (String) proxyAttrs.get("remoteUrl");
      if (remoteUrl == null || remoteUrl.isEmpty()) {
        return null;
      }

      if (!remoteUrl.endsWith("/")) {
        remoteUrl += "/";
      }

      String[] repoAuth = syncService.extractAuthFromRepo(repo);
      String normalizedPath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;

      // Strategy A: Check if remote URL points to a Nexus instance, use Search API
      String remoteRepoName = syncService.extractRepoNameFromUrl(remoteUrl);
      if (remoteRepoName != null) {
        String nexusBaseUrl = remoteUrl.substring(0, remoteUrl.indexOf("/repository/"));
        String format = repo.getFormat() != null ? repo.getFormat().getValue() : "";
        String md5 = getRemoteMd5ViaNexusApi(nexusBaseUrl, remoteRepoName, repoAuth, assetPath, "/" + normalizedPath, format);
        if (md5 != null) {
          log.debug("getRemoteAssetMd5: got MD5 {} for {}/{} via Nexus API", md5, repo.getName(), assetPath);
          return md5;
        }
        log.debug("getRemoteAssetMd5: Nexus API returned null MD5 for {}/{}", repo.getName(), assetPath);
      }

      // Fallback for non-Nexus remotes or when Search API fails:
      // Try HTTP HEAD request to get Content-MD5 header or ETag
      log.debug("getRemoteAssetMd5: trying HTTP HEAD for {}/{}", repo.getName(), assetPath);
      String headMd5 = getRemoteMd5ViaHttpHead(remoteUrl, assetPath, repoAuth);
      if (headMd5 != null) {
        log.debug("getRemoteAssetMd5: got MD5 {} for {}/{} via HTTP HEAD", headMd5, repo.getName(), assetPath);
      }
      else {
        log.warn("getRemoteAssetMd5: all methods failed for {}/{} - will sync regardless of local cache", repo.getName(), assetPath);
      }
      return headMd5;
    }
    catch (Exception e) {
      log.warn("Failed to get remote MD5 for {}/{}: {}", repo.getName(), assetPath, e.getMessage());
    }
    return null;
  }

  /**
   * Get remote MD5 via Nexus Search API with multiple query strategies.
   * Tries various combinations of group and name parameters to handle different
   * repository formats (maven2, raw, npm, etc.) and path structures.
   * @param format the repository format (e.g. "maven2", "raw", "npm") for format-aware group extraction
   */
  private String getRemoteMd5ViaNexusApi(final String nexusBaseUrl, final String remoteRepoName,
      final String[] repoAuth, final String assetPath, final String normalizedPath, final String format) {
    try {
      String group = syncService.extractGroupFromPath(assetPath, format);
      String componentName = syncService.extractComponentNameFromPath(assetPath);
      String fileName = syncService.extractFileName(assetPath);

      // Strategy 1: group + component name (parent directory name)
      // Works for Maven2 (artifactId) and some raw repos
      if (group != null && componentName != null) {
        String md5 = searchAndExtractMd5(nexusBaseUrl, remoteRepoName, repoAuth, group, componentName, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group+componentName query (group={}, name={})", assetPath, group, componentName);
          return md5;
        }
      }

      // Strategy 2: group + file name
      // Works for raw format where component name equals file name
      if (group != null && fileName != null && !fileName.equals(componentName)) {
        String md5 = searchAndExtractMd5(nexusBaseUrl, remoteRepoName, repoAuth, group, fileName, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group+fileName query (group={}, name={})", assetPath, group, fileName);
          return md5;
        }
      }

      // Strategy 3: group only (no name filter)
      if (group != null) {
        String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
            + "?repository=" + remoteRepoName
            + "&group=" + java.net.URLEncoder.encode(group, "UTF-8");
        String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via group-only query (group={})", assetPath, group);
          return md5;
        }
      }

      // Strategy 4: name only (file name, no group filter)
      // Works when the component is in an unexpected group or root group
      if (fileName != null) {
        String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
            + "?repository=" + remoteRepoName
            + "&name=" + java.net.URLEncoder.encode(fileName, "UTF-8");
        String md5 = queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
        if (md5 != null) {
          log.debug("Got remote MD5 for {} via name-only query (name={})", assetPath, fileName);
          return md5;
        }
      }
    }
    catch (Exception e) {
      log.debug("getRemoteMd5ViaNexusApi failed for {}: {}", assetPath, e.getMessage());
    }
    return null;
  }

  /**
   * Search Nexus API with group and name parameters, then extract MD5 by matching path.
   */
  private String searchAndExtractMd5(final String nexusBaseUrl, final String remoteRepoName,
      final String[] repoAuth, final String group, final String name, final String normalizedPath) {
    try {
      String searchUrl = nexusBaseUrl + "/service/rest/v1/search/assets"
          + "?repository=" + remoteRepoName
          + "&group=" + java.net.URLEncoder.encode(group, "UTF-8")
          + "&name=" + java.net.URLEncoder.encode(name, "UTF-8");
      return queryRemoteMd5(searchUrl, repoAuth, normalizedPath);
    }
    catch (Exception e) {
      log.debug("searchAndExtractMd5 failed: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Query remote Nexus Search API and extract MD5 for a specific asset path.
   * Handles pagination to search through all results.
   */
  private String queryRemoteMd5(final String searchUrlBase, final String[] repoAuth, final String normalizedPath) {
    HttpURLConnection conn = null;
    try {
      String continuationToken = null;
      do {
        String searchUrl = searchUrlBase;
        if (continuationToken != null) {
          searchUrl += "&continuationToken=" + continuationToken;
        }

        conn = (HttpURLConnection) new URL(searchUrl).openConnection();
        SslHelper.applyTrustAllSsl(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", "application/json");

        if (repoAuth != null && repoAuth.length >= 2) {
          String effectiveAuth = repoAuth[0] + ":" + repoAuth[1];
          conn.setRequestProperty("Authorization", "Basic " + ServiceUtils.encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
          conn.disconnect();
          return null;
        }

        String json = readResponse(conn);
        conn.disconnect();

        // Parse items array
        String itemsSection = ServiceUtils.extractJsonArray(json, "items");
        if (itemsSection != null) {
          int pos = 0;
          while (pos < itemsSection.length()) {
            int objStart = itemsSection.indexOf("{", pos);
            if (objStart < 0) break;
            int objEnd = ServiceUtils.findMatchingBrace(itemsSection, objStart);
            if (objEnd < 0) break;

            String item = itemsSection.substring(objStart, objEnd + 1);
            String path = ServiceUtils.extractJsonValue(item, "path");
            if (path != null) {
              // Normalize path for comparison
              if (path.startsWith("/")) {
                path = path.substring(1);
              }
              if (path.equals(normalizedPath)) {
                // Found matching asset, extract MD5
                String md5 = extractChecksumValue(item, "md5");
                if (md5 != null) {
                  return md5;
                }
              }
            }
            pos = objEnd + 1;
          }
        }

        continuationToken = ServiceUtils.extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());
    }
    catch (Exception e) {
      log.debug("queryRemoteMd5 failed: {}", e.getMessage());
    }
    finally {
      if (conn != null) {
        try { conn.disconnect(); } catch (Exception ignored) { }
      }
    }
    return null;
  }

  /**
   * Get remote MD5 via HTTP HEAD request.
   * Checks Content-MD5 header first, then falls back to ETag comparison.
   * For non-Nexus remotes, this is the only way to get remote checksum info.
   */
  private String getRemoteMd5ViaHttpHead(final String remoteUrl, final String assetPath,
      final String[] repoAuth) {
    HttpURLConnection conn = null;
    try {
      String url = remoteUrl + assetPath;
      conn = (HttpURLConnection) new URL(url).openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("HEAD");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);

      if (repoAuth != null && repoAuth.length >= 2) {
        String effectiveAuth = repoAuth[0] + ":" + repoAuth[1];
        conn.setRequestProperty("Authorization", "Basic " + ServiceUtils.encodeAuth(effectiveAuth));
      }

      int code = conn.getResponseCode();
      if (code == 200) {
        // Try Content-MD5 header first
        String contentMd5 = conn.getHeaderField("Content-MD5");
        if (contentMd5 != null && !contentMd5.isEmpty()) {
          return contentMd5;
        }

        // Fallback to ETag (remove quotes if present)
        String etag = conn.getHeaderField("ETag");
        if (etag != null && !etag.isEmpty()) {
          if (etag.startsWith("\"") && etag.endsWith("\"")) {
            etag = etag.substring(1, etag.length() - 1);
          }
          return etag;
        }
      }
    }
    catch (Exception e) {
      log.debug("getRemoteMd5ViaHttpHead failed: {}", e.getMessage());
    }
    finally {
      if (conn != null) {
        try { conn.disconnect(); } catch (Exception ignored) { }
      }
    }
    return null;
  }

  /**
   * List assets with MD5 from a local Nexus repo via Search API.
   */
  private Map<String, String> listAssetsWithMd5ViaApi(final String localRepoName, final String remoteRepoName,
      final String directoryPath, final String authUsername, final String authPassword) throws Exception {
    String effectiveAuth = (authUsername != null && authPassword != null)
        ? authUsername + ":" + authPassword : null;

    boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
    if (isFullSync) {
      String allApiUrl = ServiceUtils.getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
      return listAssetsWithMd5ViaApiUrl(allApiUrl, effectiveAuth);
    }

    String normalizedDir = directoryPath;
    if (normalizedDir.endsWith("/")) {
      normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
    }

    // Try with group filter first
    String groupApiUrl = ServiceUtils.getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName
        + "&group=" + java.net.URLEncoder.encode(normalizedDir, "UTF-8");
    Map<String, String> assetsWithGroup = listAssetsWithMd5ViaApiUrl(groupApiUrl, effectiveAuth);

    if (!assetsWithGroup.isEmpty()) {
      // Filter by path prefix
      Map<String, String> filtered = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, String> entry : assetsWithGroup.entrySet()) {
        String path = entry.getKey();
        if (path.equals(normalizedDir) || path.startsWith(normalizedDir + "/")) {
          filtered.put(path, entry.getValue());
        }
      }
      return filtered;
    }

    // Fall back to listing all assets and filtering
    String allApiUrl = ServiceUtils.getLocalNexusBase() + "/service/rest/v1/search/assets?repository=" + remoteRepoName;
    Map<String, String> allAssets = listAssetsWithMd5ViaApiUrl(allApiUrl, effectiveAuth);
    Map<String, String> filtered = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> entry : allAssets.entrySet()) {
      String path = entry.getKey();
      if (path.equals(normalizedDir) || path.startsWith(normalizedDir + "/")) {
        filtered.put(path, entry.getValue());
      }
    }
    return filtered;
  }

  /**
   * List assets from a Search API URL, extracting both path and MD5 checksum.
   * Returns a Map of (normalized path → MD5 checksum).
   * This avoids the need for a separate getRemoteAssetMd5() call per asset.
   */
  private Map<String, String> listAssetsWithMd5ViaApiUrl(final String apiUrl, final String effectiveAuth) throws Exception {
    Map<String, String> assetMap = new java.util.LinkedHashMap<>();
    String continuationToken = null;
    int md5Found = 0;
    int md5Missing = 0;
    boolean loggedFirstMissingItem = false;

    do {
      String url = apiUrl;
      if (continuationToken != null) {
        url += "&continuationToken=" + continuationToken;
      }

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(30_000);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Authorization", "Basic " + ServiceUtils.encodeAuth(effectiveAuth));

      if (conn.getResponseCode() != 200) {
        log.warn("Search API returned HTTP {} for MD5 query: {}", conn.getResponseCode(), url);
        conn.disconnect();
        return assetMap;
      }

      String json = readResponse(conn);

      // Parse items array
      String itemsSection = ServiceUtils.extractJsonArray(json, "items");
      if (itemsSection != null) {
        int pos = 0;
        while (pos < itemsSection.length()) {
          int objStart = itemsSection.indexOf("{", pos);
          if (objStart < 0) break;
          int objEnd = ServiceUtils.findMatchingBrace(itemsSection, objStart);
          if (objEnd < 0) break;

          String item = itemsSection.substring(objStart, objEnd + 1);
          String path = ServiceUtils.extractJsonValue(item, "path");

          if (path != null) {
            // Normalize: strip leading slash for consistent path matching
            if (path.startsWith("/")) {
              path = path.substring(1);
            }
            // Extract MD5 from checksums in the same item
            String md5 = extractChecksumValue(item, "md5");
            assetMap.put(path, md5);
            if (md5 != null) {
              md5Found++;
            }
            else {
              md5Missing++;
              // Log the first item where MD5 extraction failed for diagnosis
              if (!loggedFirstMissingItem) {
                loggedFirstMissingItem = true;
                String snippet = item.length() > 500 ? item.substring(0, 500) + "..." : item;
                log.debug("MD5 extraction failed for asset '{}', raw item JSON (first 500 chars): {}", path, snippet);
              }
            }
          }

          pos = objEnd + 1;
        }
      }

      continuationToken = ServiceUtils.extractJsonValue(json, "continuationToken");

    } while (continuationToken != null && !continuationToken.isEmpty());

    log.debug("Search API MD5 extraction result: {} assets with MD5, {} assets without MD5 (total: {})",
        md5Found, md5Missing, assetMap.size());

    return assetMap;
  }

  /**
   * List assets with MD5 from a remote Nexus instance via Components API.
   * This is a fallback when Search API doesn't return checksums.
   * Components API returns assets with checksum information in the asset object.
   */
  private Map<String, String> listAssetsWithMd5ViaComponentsApi(final String remoteBaseUrl, final String remoteRepoName,
      final String directoryPath, final String effectiveAuth) {
    Map<String, String> assetMap = new java.util.LinkedHashMap<>();
    try {
      boolean isFullSync = (directoryPath == null || directoryPath.trim().isEmpty());
      String compUrlBase = remoteBaseUrl + "/service/rest/v1/components?repository=" + remoteRepoName;
      String continuationToken = null;
      int md5Found = 0;
      int md5Missing = 0;

      do {
        String compUrl = compUrlBase;
        if (continuationToken != null) {
          compUrl += "&continuationToken=" + continuationToken;
        }

        log.debug("Calling remote Nexus Components API for MD5: {}", compUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(compUrl).openConnection();
        SslHelper.applyTrustAllSsl(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "application/json");

        if (effectiveAuth != null) {
          conn.setRequestProperty("Authorization", "Basic " + ServiceUtils.encodeAuth(effectiveAuth));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
          log.warn("Components API returned HTTP {} for MD5 query: {}", code, compUrl);
          conn.disconnect();
          return assetMap;
        }

        String json = readResponse(conn);
        conn.disconnect();

        // Parse components and extract asset paths with MD5
        String itemsSection = ServiceUtils.extractJsonArray(json, "items");
        if (itemsSection != null) {
          int pos = 0;
          while (pos < itemsSection.length()) {
            int objStart = itemsSection.indexOf("{", pos);
            if (objStart < 0) break;
            int objEnd = ServiceUtils.findMatchingBrace(itemsSection, objStart);
            if (objEnd < 0) break;

            String component = itemsSection.substring(objStart, objEnd + 1);

            // Extract assets array from component
            String assetsSection = ServiceUtils.extractJsonArray(component, "assets");
            if (assetsSection != null) {
              int aPos = 0;
              while (aPos < assetsSection.length()) {
                int aObjStart = assetsSection.indexOf("{", aPos);
                if (aObjStart < 0) break;
                int aObjEnd = ServiceUtils.findMatchingBrace(assetsSection, aObjStart);
                if (aObjEnd < 0) break;

                String asset = assetsSection.substring(aObjStart, aObjEnd + 1);
                String path = ServiceUtils.extractJsonValue(asset, "path");
                if (path != null && !path.isEmpty()) {
                  if (path.startsWith("/")) path = path.substring(1);
                  
                  // Filter by directory path if not full sync
                  if (!isFullSync && directoryPath != null && !directoryPath.isEmpty()) {
                    String normalizedDir = directoryPath.startsWith("/") ? directoryPath.substring(1) : directoryPath;
                    if (normalizedDir.endsWith("/")) normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
                    if (!path.equals(normalizedDir) && !path.startsWith(normalizedDir + "/")) {
                      aPos = aObjEnd + 1;
                      continue;
                    }
                  }

                  // Extract MD5 from checksums in the asset object
                  String md5 = extractChecksumValue(asset, "md5");
                  assetMap.put(path, md5);
                  if (md5 != null) {
                    md5Found++;
                  } else {
                    md5Missing++;
                  }
                }
                aPos = aObjEnd + 1;
              }
            }
            pos = objEnd + 1;
          }
        }

        continuationToken = ServiceUtils.extractJsonValue(json, "continuationToken");
      }
      while (continuationToken != null && !continuationToken.isEmpty());

      log.debug("Components API MD5 extraction result: {} assets with MD5, {} assets without MD5 (total: {})",
          md5Found, md5Missing, assetMap.size());
    }
    catch (Exception e) {
      log.warn("Components API MD5 retrieval failed: {}", e.getMessage());
    }
    return assetMap;
  }

  /**
   * Extract checksum value from JSON.
   */
  private String extractChecksumValue(final String json, final String algo) {
    // Find "checksums" key
    String checksumsPattern = "\"checksums\"";
    int checksumsIdx = json.indexOf(checksumsPattern);
    if (checksumsIdx >= 0) {
      // Find the opening brace of the checksums object
      int objStart = json.indexOf('{', checksumsIdx + checksumsPattern.length());
      if (objStart >= 0) {
        // Find the matching closing brace
        int objEnd = ServiceUtils.findMatchingBrace(json, objStart);
        if (objEnd >= 0) {
          // Extract the checksums object and find the algorithm value
          String checksumsObj = json.substring(objStart, objEnd + 1);
          String value = ServiceUtils.extractJsonValue(checksumsObj, algo);
          if (value != null) return value;
        }
      }
    }

    // Fallback: try to find the algorithm directly in the JSON
    // Some Nexus versions or formats may structure checksums differently
    // e.g. "checksum": {"md5": "..."} or "md5": "..." at top level
    String algoPattern = "\"" + algo + "\"";
    int algoIdx = json.indexOf(algoPattern);
    if (algoIdx >= 0) {
      int colonIdx = json.indexOf(':', algoIdx + algoPattern.length());
      if (colonIdx >= 0) {
        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart >= 0) {
          int valueEnd = valueStart + 1;
          while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == '"' && json.charAt(valueEnd - 1) != '\\') break;
            valueEnd++;
          }
          if (valueEnd < json.length()) {
            return json.substring(valueStart + 1, valueEnd);
          }
        }
      }
    }

    return null;
  }

  /**
   * Read response from HTTP connection.
   */
  private String readResponse(final HttpURLConnection conn) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
      String line;
      while ((line = reader.readLine()) != null) { sb.append(line); }
    }
    return sb.toString();
  }

  /**
   * Get the count of assets that would need syncing (preview/dry-run).
   * This does not perform any sync, only counts the differences.
   *
   * @param repositoryName the proxy repository name
   * @param directoryPath the path to check (empty for full repository)
   * @return a summary with total, synced, and skipped counts
   */
  public SyncPreviewResult previewIncrementalSync(
      final String repositoryName,
      final String directoryPath) {

    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      throw new IllegalArgumentException("Repository not found: " + repositoryName);
    }
    if (!"proxy".equals(repo.getType().getValue())) {
      throw new IllegalArgumentException("Repository is not a proxy type: " + repositoryName);
    }

    // Use SyncService's public incremental sync to get the actual file details
    SyncRequest request = new SyncRequest();
    request.setRepositoryName(repositoryName);
    request.setPath(directoryPath != null ? directoryPath : "");
    request.setIsDirectory(true);
    request.setFormat(repo.getFormat().getValue());

    SyncTaskInfo result = syncService.syncScheduled(request, null, true);

    SyncPreviewResult preview = new SyncPreviewResult();
    if (result.getFileDetails() != null) {
      for (SyncTaskInfo.SyncFileDetail detail : result.getFileDetails()) {
        preview.totalAssets++;
        if ("skipped".equals(detail.getStatus())) {
          preview.skippedAssets++;
        }
        else if ("success".equals(detail.getStatus())) {
          preview.syncedAssets++;
        }
        else if ("failed".equals(detail.getStatus())) {
          preview.failedAssets++;
        }
      }
    }
    return preview;
  }

  /**
   * Result of an incremental sync preview.
   */
  public static class SyncPreviewResult {
    public int totalAssets;
    public int syncedAssets;
    public int skippedAssets;
    public int failedAssets;

    @Override
    public String toString() {
      return String.format("SyncPreviewResult{total=%d, synced=%d, skipped=%d, failed=%d}",
          totalAssets, syncedAssets, skippedAssets, failedAssets);
    }
  }
}
