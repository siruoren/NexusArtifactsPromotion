package com.nexus.artifacts.promotion.model;

import java.util.List;

/**
 * Request model for Docker image promotion or sync operations.
 * Supports three modes:
 * 1. Promote/sync all tags of an image (tags = null or empty)
 * 2. Promote/sync specific tags of an image (tags = ["latest", "v1.0", ...])
 * 3. Promote/sync all images under a directory prefix (imagePrefix = "project/8.3.2.891")
 */
public class DockerImageRequest {

  /** Source repository name (for promotion) or proxy repository name (for sync) */
  private String sourceRepository;

  /** Target repository name (only for promotion, not used for sync) */
  private String targetRepository;

  /** Docker image name (e.g. "myapp/backend", "nginx") */
  private String image;

  /** List of specific tags to promote/sync. If null or empty, all tags are processed. */
  private List<String> tags;

  /** Repository format, should be "docker" */
  private String format;

  /** If true, promote/sync all images in the repository (directory level). image field is ignored. */
  private boolean allImages;

  /**
   * Directory prefix for syncing multiple images under a path.
   * For example, "project/8.3.2.891" will find and sync all images like
   * project/8.3.2.891/app1, project/8.3.2.891/app2, etc.
   * When set, takes precedence over image field for sync operations.
   */
  private String imagePrefix;

  /** If true, only sync files that differ from local cache (MD5/digest comparison) */
  private boolean incrementalSync;

  public DockerImageRequest() {}

  public void validate() {
    if (sourceRepository == null || sourceRepository.trim().isEmpty()) {
      throw new IllegalArgumentException("sourceRepository is required");
    }
    if (format == null || format.trim().isEmpty()) {
      throw new IllegalArgumentException("format is required");
    }
    // image is required only when not doing all-images or prefix mode
    if (!allImages && (imagePrefix == null || imagePrefix.trim().isEmpty())
        && (image == null || image.trim().isEmpty())) {
      throw new IllegalArgumentException("image is required");
    }
    // Path traversal check
    if (image != null && image.contains("..")) {
      throw new IllegalArgumentException("Invalid image name");
    }
    if (imagePrefix != null && imagePrefix.contains("..")) {
      throw new IllegalArgumentException("Invalid image prefix");
    }
  }

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getTargetRepository() { return targetRepository; }
  public void setTargetRepository(String targetRepository) { this.targetRepository = targetRepository; }

  public String getImage() { return image; }
  public void setImage(String image) { this.image = image; }

  public List<String> getTags() { return tags; }
  public void setTags(List<String> tags) { this.tags = tags; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  public boolean isAllTags() {
    return tags == null || tags.isEmpty();
  }

  public boolean isAllImages() {
    return allImages;
  }

  public void setAllImages(boolean allImages) {
    this.allImages = allImages;
  }

  public String getImagePrefix() { return imagePrefix; }
  public void setImagePrefix(String imagePrefix) { this.imagePrefix = imagePrefix; }

  public boolean isPrefixMode() {
    return imagePrefix != null && !imagePrefix.trim().isEmpty();
  }

  public boolean isIncrementalSync() { return incrementalSync; }
  public void setIncrementalSync(boolean incrementalSync) { this.incrementalSync = incrementalSync; }
}
