package com.nexus.artifacts.promotion.model;

import java.util.List;

/**
 * Request model for Docker image promotion or sync operations.
 * Supports two modes:
 * 1. Promote/sync all tags of an image (tags = null or empty)
 * 2. Promote/sync specific tags of an image (tags = ["latest", "v1.0", ...])
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

  public DockerImageRequest() {}

  public void validate() {
    if (sourceRepository == null || sourceRepository.trim().isEmpty()) {
      throw new IllegalArgumentException("sourceRepository is required");
    }
    if (image == null || image.trim().isEmpty()) {
      throw new IllegalArgumentException("image is required");
    }
    if (format == null || format.trim().isEmpty()) {
      throw new IllegalArgumentException("format is required");
    }
    // Path traversal check
    if (image.contains("..")) {
      throw new IllegalArgumentException("Invalid image name");
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
}
