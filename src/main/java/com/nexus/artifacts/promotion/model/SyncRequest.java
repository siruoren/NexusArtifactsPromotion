package com.nexus.artifacts.promotion.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request DTO for remote repository sync.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncRequest {

  private String repositoryName;
  private String path;
  private boolean isDirectory;
  private String format;

  public SyncRequest() {}

  public String getRepositoryName() { return repositoryName; }
  public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }

  public boolean isDirectory() { return isDirectory; }
  public void setDirectory(boolean directory) { isDirectory = directory; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  /**
   * Validate the request.
   */
  public void validate() {
    if (repositoryName == null || repositoryName.trim().isEmpty()) {
      throw new IllegalArgumentException("repositoryName is required");
    }
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalArgumentException("path is required");
    }
    if (format == null || format.trim().isEmpty()) {
      throw new IllegalArgumentException("format is required");
    }
    if (path.contains("..") || path.contains("\0")) {
      throw new IllegalArgumentException("Invalid path: path traversal detected");
    }
  }
}
