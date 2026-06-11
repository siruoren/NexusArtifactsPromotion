package com.nexus.artifacts.promotion.security;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CPrivilegeBuilder;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security contributor that automatically creates promotion privilege entries
 * for all existing repositories.
 *
 * Uses Provider&lt;RepositoryManager&gt; to avoid circular dependency:
 * SecurityContributor -> RepositoryManager -> SecuritySystem -> SecurityContributor
 */
@Named
@Singleton
public class PromotionSecurityContributor
    implements SecurityContributor
{
  private static final Logger log = LoggerFactory.getLogger(PromotionSecurityContributor.class);

  private final Provider<RepositoryManager> repositoryManagerProvider;

  @Inject
  public PromotionSecurityContributor(final Provider<RepositoryManager> repositoryManagerProvider) {
    this.repositoryManagerProvider = repositoryManagerProvider;
  }

  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    try {
      CPrivilege wildcardPrivilege = new CPrivilegeBuilder()
          .type("application")
          .id("nexus-artifacts-promotion-promote")
          .property("domain", "nexus-artifacts-promotion")
          .property("actions", "promote")
          .create();
      wildcardPrivilege.setName("nx-artifacts-promotion-all");
      wildcardPrivilege.setDescription("Full promotion permission for all repositories");
      config.addPrivilege(wildcardPrivilege);
      log.info("Created wildcard promotion privilege");
    }
    catch (Exception e) {
      log.warn("Failed to create wildcard promotion privilege: {}", e.getMessage(), e);
    }

    try {
      RepositoryManager repoManager = repositoryManagerProvider.get();
      if (repoManager == null) {
        log.warn("RepositoryManager not available, skipping per-repository privileges");
        return config;
      }

      int count = 0;
      for (Repository repo : repoManager.browse()) {
        try {
          String repoName = repo.getName();
          String format = repo.getFormat().getValue();
          String domain = "nexus-artifacts-promotion-" + sanitize(repoName) + "-" + sanitize(format);
          String id = domain + "-promote";

          CPrivilege repoPrivilege = new CPrivilegeBuilder()
              .type("application")
              .id(id)
              .property("domain", domain)
              .property("actions", "promote")
              .create();
          repoPrivilege.setName("Promote from " + repoName + " (" + format + ")");
          repoPrivilege.setDescription("Permission to promote artifacts from repository " + repoName + " with format " + format);
          config.addPrivilege(repoPrivilege);
          count++;
          log.debug("Created promotion privilege for repository: {} (format: {})", repoName, format);
        }
        catch (Exception e) {
          log.debug("Failed to create promotion privilege for repository: {}", e.getMessage());
        }
      }
      log.info("Promotion security contributor created privileges for {} repositories", count);
    }
    catch (Exception e) {
      log.warn("Failed to create per-repository promotion privileges: {}", e.getMessage(), e);
    }

    return config;
  }

  public static CPrivilege createPromotionPrivilege(final String repositoryName, final String format) {
    String domain = "nexus-artifacts-promotion-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-promote";
    CPrivilege privilege = new CPrivilegeBuilder()
        .type("application")
        .id(id)
        .property("domain", domain)
        .property("actions", "promote")
        .create();
    privilege.setName("Promote from " + repositoryName + " (" + format + ")");
    privilege.setDescription("Permission to promote artifacts from repository " + repositoryName + " with format " + format);
    return privilege;
  }

  public static String getPrivilegeId(final String repositoryName, final String format) {
    String domain = "nexus-artifacts-promotion-" + sanitize(repositoryName) + "-" + sanitize(format);
    return domain + "-promote";
  }

  private static String sanitize(final String input) {
    if (input == null || input.isEmpty()) {
      return "_";
    }
    return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
