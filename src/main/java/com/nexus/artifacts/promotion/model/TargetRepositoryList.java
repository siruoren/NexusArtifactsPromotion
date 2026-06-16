package com.nexus.artifacts.promotion.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for listing promotion-eligible target repositories.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TargetRepositoryList {

  private String sourceRepository;
  private String format;
  private List<TargetRepository> repositories;

  public TargetRepositoryList() {}

  public String getSourceRepository() { return sourceRepository; }
  public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }

  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }

  public List<TargetRepository> getRepositories() { return repositories; }
  public void setRepositories(List<TargetRepository> repositories) { this.repositories = repositories; }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class TargetRepository {
    private String name;
    private String format;
    private String type;
    private String url;

    public TargetRepository() {}

    public TargetRepository(String name, String format, String type, String url) {
      this.name = name;
      this.format = format;
      this.type = type;
      this.url = url;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
  }
}
