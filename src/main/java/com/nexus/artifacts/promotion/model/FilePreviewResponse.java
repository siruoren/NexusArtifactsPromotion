package com.nexus.artifacts.promotion.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for preview of files involved in a promotion or sync operation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilePreviewResponse {

  private String sourceRepository;
  private String targetRepository;
  private String basePath;
  private List<FileEntry> files;
  private int totalCount;
  private long totalSize;

  public FilePreviewResponse() {}

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getTargetRepository() { return targetRepository; }
  public void setTargetRepository(String targetRepository) { this.targetRepository = targetRepository; }

  public String getBasePath() { return basePath; }
  public void setBasePath(String basePath) { this.basePath = basePath; }

  public List<FileEntry> getFiles() { return files; }
  public void setFiles(List<FileEntry> files) { this.files = files; }

  public int getTotalCount() { return totalCount; }
  public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

  public long getTotalSize() { return totalSize; }
  public void setTotalSize(long totalSize) { this.totalSize = totalSize; }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class FileEntry {
    private String path;
    private String type; // "directory", "file", "image"
    private long size;
    private boolean existsInTarget;

    public FileEntry() {}

    public FileEntry(String path, String type, long size, boolean existsInTarget) {
      this.path = path;
      this.type = type;
      this.size = size;
      this.existsInTarget = existsInTarget;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public boolean isExistsInTarget() { return existsInTarget; }
    public void setExistsInTarget(boolean existsInTarget) { this.existsInTarget = existsInTarget; }
  }
}
