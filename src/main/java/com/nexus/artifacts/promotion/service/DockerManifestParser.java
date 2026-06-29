package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for Docker and OCI image manifests.
 * Supports multiple manifest formats:
 * <ul>
 *   <li>Docker Manifest V2 Schema 2 (application/vnd.docker.distribution.manifest.v2+json)</li>
 *   <li>OCI Image Manifest V1 (application/vnd.oci.image.manifest.v1+json)</li>
 *   <li>Docker Manifest List V2 / Fat Manifest (application/vnd.docker.distribution.manifest.list.v2+json)</li>
 *   <li>OCI Image Index V1 (application/vnd.oci.image.index.v1+json)</li>
 * </ul>
 */
public class DockerManifestParser {

  private static final Logger log = LoggerFactory.getLogger(DockerManifestParser.class);

  // Docker media types
  public static final String MEDIA_TYPE_DOCKER_V2 = "application/vnd.docker.distribution.manifest.v2+json";
  public static final String MEDIA_TYPE_DOCKER_V1_SIGNED = "application/vnd.docker.distribution.manifest.v1+prettyjws";
  public static final String MEDIA_TYPE_DOCKER_V1 = "application/vnd.docker.distribution.manifest.v1+json";
  public static final String MEDIA_TYPE_FAT_MANIFEST = "application/vnd.docker.distribution.manifest.list.v2+json";

  // OCI media types
  public static final String MEDIA_TYPE_OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  public static final String MEDIA_TYPE_OCI_INDEX = "application/vnd.oci.image.index.v1+json";

  /**
   * Parsed result of a manifest.
   */
  public static class DockerManifest {
    private String mediaType;
    private String schemaVersion;
    private String configDigest;
    private List<String> layerDigests = new ArrayList<>();
    private List<ManifestReference> manifestReferences = new ArrayList<>();
    private boolean fatManifest;

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getConfigDigest() { return configDigest; }
    public void setConfigDigest(String configDigest) { this.configDigest = configDigest; }

    public List<String> getLayerDigests() { return layerDigests; }
    public void setLayerDigests(List<String> layerDigests) { this.layerDigests = layerDigests; }

    public List<ManifestReference> getManifestReferences() { return manifestReferences; }
    public void setManifestReferences(List<ManifestReference> manifestReferences) { this.manifestReferences = manifestReferences; }

    public boolean isFatManifest() { return fatManifest; }
    public void setFatManifest(boolean fatManifest) { this.fatManifest = fatManifest; }

    /**
     * Get all blob digests referenced by this manifest (config + layers).
     * For fat manifests, returns the sub-manifest digests instead.
     */
    public Set<String> getAllBlobDigests() {
      Set<String> digests = new HashSet<>();
      if (configDigest != null) {
        digests.add(configDigest);
      }
      digests.addAll(layerDigests);
      return digests;
    }

    /**
     * Get all sub-manifest digests (for fat manifests).
     */
    public Set<String> getSubManifestDigests() {
      Set<String> digests = new HashSet<>();
      for (ManifestReference ref : manifestReferences) {
        digests.add(ref.getDigest());
      }
      return digests;
    }
  }

  /**
   * Reference to a platform-specific manifest in a fat manifest.
   */
  public static class ManifestReference {
    private String digest;
    private String mediaType;
    private String platformOs;
    private String platformArch;
    private String platformVariant;
    private long size;

    public ManifestReference(String digest) {
      this.digest = digest;
    }

    public String getDigest() { return digest; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getPlatformOs() { return platformOs; }
    public void setPlatformOs(String platformOs) { this.platformOs = platformOs; }
    public String getPlatformArch() { return platformArch; }
    public void setPlatformArch(String platformArch) { this.platformArch = platformArch; }
    public String getPlatformVariant() { return platformVariant; }
    public void setPlatformVariant(String platformVariant) { this.platformVariant = platformVariant; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(digest);
      if (platformOs != null || platformArch != null) {
        sb.append(" (").append(platformOs != null ? platformOs : "?")
            .append("/").append(platformArch != null ? platformArch : "?");
        if (platformVariant != null) {
          sb.append("/").append(platformVariant);
        }
        sb.append(")");
      }
      return sb.toString();
    }
  }

  /**
   * Parse a manifest JSON string.
   * If mediaType is null, attempts auto-detection from the JSON content.
   *
   * @param manifestJson the raw manifest JSON
   * @param mediaType the Content-Type of the manifest (may be null)
   * @return parsed DockerManifest
   * @throws IOException if the manifest format is unsupported or invalid
   */
  public static DockerManifest parse(final String manifestJson, final String mediaType) throws IOException {
    if (manifestJson == null || manifestJson.trim().isEmpty()) {
      throw new IOException("Manifest content is empty");
    }

    String resolvedMediaType = mediaType;
    if (resolvedMediaType == null || resolvedMediaType.isEmpty()) {
      resolvedMediaType = detectManifestType(manifestJson);
    }

    log.debug("Parsing manifest with media type: {}", resolvedMediaType);

    if (MEDIA_TYPE_DOCKER_V2.equals(resolvedMediaType) || MEDIA_TYPE_OCI_MANIFEST.equals(resolvedMediaType)) {
      return parseSingleManifest(manifestJson, resolvedMediaType);
    }
    else if (MEDIA_TYPE_FAT_MANIFEST.equals(resolvedMediaType) || MEDIA_TYPE_OCI_INDEX.equals(resolvedMediaType)) {
      return parseFatManifest(manifestJson, resolvedMediaType);
    }
    else if (MEDIA_TYPE_DOCKER_V1_SIGNED.equals(resolvedMediaType) || MEDIA_TYPE_DOCKER_V1.equals(resolvedMediaType)) {
      // V1 manifests don't have config/layers structure - treat as single manifest
      log.warn("V1 manifest format detected, limited support");
      DockerManifest result = new DockerManifest();
      result.setMediaType(resolvedMediaType);
      result.setFatManifest(false);
      return result;
    }
    else {
      // Unknown type - try to parse as single manifest (best effort)
      log.warn("Unknown manifest media type: {}, attempting best-effort parse", resolvedMediaType);
      return parseSingleManifest(manifestJson, resolvedMediaType);
    }
  }

  /**
   * Auto-detect manifest type from JSON content.
   */
  private static String detectManifestType(final String json) {
    // Check for manifest list (fat manifest) - has "manifests" array at top level
    // and "mediaType" indicating list type
    String topMediaType = ServiceUtils.extractJsonValue(json, "mediaType");
    if (topMediaType != null) {
      if (topMediaType.contains("manifest.list") || topMediaType.contains("image.index")) {
        return topMediaType;
      }
      if (topMediaType.contains("manifest.v2") || topMediaType.contains("image.manifest")) {
        return topMediaType;
      }
    }

    // Heuristic: if "manifests" array exists at top level, it's a fat manifest
    String manifestsArray = ServiceUtils.extractJsonArray(json, "manifests");
    if (manifestsArray != null && !manifestsArray.trim().isEmpty()) {
      // Check if it also has "config" - if so, it's a single manifest with "manifests" in annotations
      String configDigest = extractConfigDigest(json);
      if (configDigest == null) {
        // No config, has manifests array → fat manifest
        return MEDIA_TYPE_FAT_MANIFEST;
      }
    }

    // Default: assume Docker V2 Schema 2
    return MEDIA_TYPE_DOCKER_V2;
  }

  /**
   * Parse a single-platform manifest (Docker V2 Schema 2 or OCI Image Manifest).
   */
  private static DockerManifest parseSingleManifest(final String json, final String mediaType) throws IOException {
    DockerManifest manifest = new DockerManifest();
    manifest.setMediaType(mediaType);
    manifest.setFatManifest(false);

    // Extract schema version
    String schemaVersion = ServiceUtils.extractJsonValue(json, "schemaVersion");
    manifest.setSchemaVersion(schemaVersion);

    // Extract config digest
    String configDigest = extractConfigDigest(json);
    manifest.setConfigDigest(configDigest);

    // Extract layer digests
    List<String> layerDigests = extractLayerDigests(json);
    manifest.setLayerDigests(layerDigests);

    log.debug("Parsed single manifest: config={}, layers={}", configDigest, layerDigests.size());
    return manifest;
  }

  /**
   * Parse a fat manifest / manifest list (multi-platform).
   */
  private static DockerManifest parseFatManifest(final String json, final String mediaType) throws IOException {
    DockerManifest manifest = new DockerManifest();
    manifest.setMediaType(mediaType);
    manifest.setFatManifest(true);

    String schemaVersion = ServiceUtils.extractJsonValue(json, "schemaVersion");
    manifest.setSchemaVersion(schemaVersion);

    // Extract manifest references (one per platform)
    List<ManifestReference> references = extractManifestReferences(json);
    manifest.setManifestReferences(references);

    log.info("Parsed fat manifest with {} platform references: {}", references.size(), references);
    return manifest;
  }

  /**
   * Extract config digest from a single manifest.
   * Format: "config": { "digest": "sha256:..." }
   */
  private static String extractConfigDigest(final String json) {
    try {
      String searchKey = "\"config\"";
      int keyIdx = json.indexOf(searchKey);
      if (keyIdx < 0) return null;

      int objStart = json.indexOf('{', keyIdx + searchKey.length());
      if (objStart < 0) return null;
      int objEnd = ServiceUtils.findMatchingBrace(json, objStart);
      if (objEnd < 0) return null;

      String obj = json.substring(objStart, objEnd + 1);
      return ServiceUtils.extractJsonValue(obj, "digest");
    }
    catch (Exception e) {
      return null;
    }
  }

  /**
   * Extract layer digests from the "layers" array.
   */
  private static List<String> extractLayerDigests(final String json) {
    List<String> digests = new ArrayList<>();
    try {
      String layersArray = ServiceUtils.extractJsonArray(json, "layers");
      if (layersArray == null) return digests;

      int pos = 0;
      while (pos < layersArray.length()) {
        int objStart = layersArray.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = ServiceUtils.findMatchingBrace(layersArray, objStart);
        if (objEnd < 0) break;

        String obj = layersArray.substring(objStart, objEnd + 1);
        String digest = ServiceUtils.extractJsonValue(obj, "digest");
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
   * Extract manifest references from a fat manifest's "manifests" array.
   */
  private static List<ManifestReference> extractManifestReferences(final String json) {
    List<ManifestReference> references = new ArrayList<>();
    try {
      String manifestsArray = ServiceUtils.extractJsonArray(json, "manifests");
      if (manifestsArray == null) return references;

      int pos = 0;
      while (pos < manifestsArray.length()) {
        int objStart = manifestsArray.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = ServiceUtils.findMatchingBrace(manifestsArray, objStart);
        if (objEnd < 0) break;

        String obj = manifestsArray.substring(objStart, objEnd + 1);
        String digest = ServiceUtils.extractJsonValue(obj, "digest");
        if (digest != null && !digest.isEmpty()) {
          ManifestReference ref = new ManifestReference(digest);
          ref.setMediaType(ServiceUtils.extractJsonValue(obj, "mediaType"));

          // Extract platform info
          String platformOs = extractPlatformField(obj, "os");
          String platformArch = extractPlatformField(obj, "architecture");
          String platformVariant = extractPlatformField(obj, "variant");
          ref.setPlatformOs(platformOs);
          ref.setPlatformArch(platformArch);
          ref.setPlatformVariant(platformVariant);

          String sizeStr = ServiceUtils.extractJsonValue(obj, "size");
          if (sizeStr != null) {
            try { ref.setSize(Long.parseLong(sizeStr)); } catch (NumberFormatException e) { /* ignore */ }
          }

          references.add(ref);
        }
        pos = objEnd + 1;
      }
    }
    catch (Exception e) {
      log.debug("Failed to extract manifest references: {}", e.getMessage());
    }
    return references;
  }

  /**
   * Extract a field from the "platform" sub-object in a manifest reference.
   */
  private static String extractPlatformField(final String manifestRefObj, final String field) {
    try {
      String platformKey = "\"platform\"";
      int platformIdx = manifestRefObj.indexOf(platformKey);
      if (platformIdx < 0) return null;

      int objStart = manifestRefObj.indexOf('{', platformIdx + platformKey.length());
      if (objStart < 0) return null;
      int objEnd = ServiceUtils.findMatchingBrace(manifestRefObj, objStart);
      if (objEnd < 0) return null;

      String platformObj = manifestRefObj.substring(objStart, objEnd + 1);
      return ServiceUtils.extractJsonValue(platformObj, field);
    }
    catch (Exception e) {
      return null;
    }
  }

}
