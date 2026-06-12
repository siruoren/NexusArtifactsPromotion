package com.nexus.artifacts.promotion.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for artifact promotion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionRequest {

  private String sourceRepository;
  private String targetRepository;
  private String path;
  private boolean isDirectory;
  private String format;
  /**
   * File list for directory promotion.
   * When isDirectory=true, this contains the files to promote one by one.
   */
  private List<String> files;

  public PromotionRequest() {}

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getTargetRepository() { return targetRepository; }
  public void setTargetRepository(String targetRepository) { this.targetRepository = targetRepository; }

  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }

  @JsonProperty("isDirectory")
  public boolean isDirectory() { return isDirectory; }
  @JsonProperty("isDirectory")
  public void setDirectory(boolean directory) { isDirectory = directory; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  public List<String> getFiles() { return files; }
  public void setFiles(List<String> files) { this.files = files; }

  /**
   * Validate the request, throwing IllegalArgumentException if invalid.
   */
  public void validate() {
    if (sourceRepository == null || sourceRepository.trim().isEmpty()) {
      throw new IllegalArgumentException("sourceRepository is required");
    }
    if (targetRepository == null || targetRepository.trim().isEmpty()) {
      throw new IllegalArgumentException("targetRepository is required");
    }
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalArgumentException("path is required");
    }
    if (format == null || format.trim().isEmpty()) {
      throw new IllegalArgumentException("format is required");
    }
    if (sourceRepository.equals(targetRepository)) {
      throw new IllegalArgumentException("sourceRepository and targetRepository must be different");
    }
    // Sanitize path - prevent path traversal
    if (path.contains("..") || path.contains("\0")) {
      throw new IllegalArgumentException("Invalid path: path traversal detected");
    }
  }
}
