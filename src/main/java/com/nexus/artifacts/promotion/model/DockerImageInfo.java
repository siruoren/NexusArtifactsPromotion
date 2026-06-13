package com.nexus.artifacts.promotion.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Docker image with its name and available tags.
 */
public class DockerImageInfo {

  /** Image name (e.g. "myapp/backend", "nginx") */
  private String name;

  /** Available tags for this image */
  private List<String> tags = new ArrayList<>();

  public DockerImageInfo() {}

  public DockerImageInfo(String name) {
    this.name = name;
  }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public List<String> getTags() { return tags; }
  public void setTags(List<String> tags) { this.tags = tags; }

  public void addTag(String tag) {
    if (tag != null && !tag.isEmpty() && !tags.contains(tag)) {
      tags.add(tag);
    }
  }
}
