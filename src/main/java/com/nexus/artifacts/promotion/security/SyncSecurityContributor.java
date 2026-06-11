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
 * Security contributor that automatically creates sync privilege entries
 * for all existing repositories.
 *
 * Uses Provider&lt;RepositoryManager&gt; to avoid circular dependency.
 */
@Named
@Singleton
public class SyncSecurityContributor
    implements SecurityContributor
{
  private static final Logger log = LoggerFactory.getLogger(SyncSecurityContributor.class);

  private final Provider<RepositoryManager> repositoryManagerProvider;

  @Inject
  public SyncSecurityContributor(final Provider<RepositoryManager> repositoryManagerProvider) {
    this.repositoryManagerProvider = repositoryManagerProvider;
  }

  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    try {
      CPrivilege wildcardPrivilege = new CPrivilegeBuilder()
          .type("application")
          .id("nexus-artifacts-sync-sync")
          .property("domain", "nexus-artifacts-sync")
          .property("actions", "sync")
          .create();
      wildcardPrivilege.setName("nx-artifacts-sync-all");
      wildcardPrivilege.setDescription("Full sync permission for all repositories");
      config.addPrivilege(wildcardPrivilege);
      log.info("Created wildcard sync privilege");
    }
    catch (Exception e) {
      log.warn("Failed to create wildcard sync privilege: {}", e.getMessage(), e);
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
          String domain = "nexus-artifacts-sync-" + sanitize(repoName) + "-" + sanitize(format);
          String id = domain + "-sync";

          CPrivilege repoPrivilege = new CPrivilegeBuilder()
              .type("application")
              .id(id)
              .property("domain", domain)
              .property("actions", "sync")
              .create();
          repoPrivilege.setName("Sync from " + repoName + " (" + format + ")");
          repoPrivilege.setDescription("Permission to sync artifacts from remote repository " + repoName + " with format " + format);
          config.addPrivilege(repoPrivilege);
          count++;
          log.debug("Created sync privilege for repository: {} (format: {})", repoName, format);
        }
        catch (Exception e) {
          log.debug("Failed to create sync privilege for repository: {}", e.getMessage());
        }
      }
      log.info("Sync security contributor created privileges for {} repositories", count);
    }
    catch (Exception e) {
      log.warn("Failed to create per-repository sync privileges: {}", e.getMessage(), e);
    }

    return config;
  }

  public static CPrivilege createSyncPrivilege(final String repositoryName, final String format) {
    String domain = "nexus-artifacts-sync-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-sync";
    CPrivilege privilege = new CPrivilegeBuilder()
        .type("application")
        .id(id)
        .property("domain", domain)
        .property("actions", "sync")
        .create();
    privilege.setName("Sync from " + repositoryName + " (" + format + ")");
    privilege.setDescription("Permission to sync artifacts from remote repository " + repositoryName + " with format " + format);
    return privilege;
  }

  public static String getPrivilegeId(final String repositoryName, final String format) {
    String domain = "nexus-artifacts-sync-" + sanitize(repositoryName) + "-" + sanitize(format);
    return domain + "-sync";
  }

  private static String sanitize(final String input) {
    if (input == null || input.isEmpty()) {
      return "_";
    }
    return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
