package com.nexus.artifacts.promotion.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Response model for listing Docker images in a repository.
 */
public class DockerImageListResponse {

  /** Repository name */
  private String repository;

  /** Repository format */
  private String format;

  /** List of Docker images */
  private List<DockerImageInfo> images = new ArrayList<>();

  /** Total image count */
  private int totalCount;

  public DockerImageListResponse() {}

  public String getRepository() { return repository; }
  public void setRepository(String repository) { this.repository = repository; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  public List<DockerImageInfo> getImages() { return images; }
  public void setImages(List<DockerImageInfo> images) { this.images = images; }

  public int getTotalCount() { return totalCount; }
  public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

  public void addImage(DockerImageInfo image) {
    images.add(image);
    totalCount = images.size();
  }
}
