package com.nexus.artifacts.promotion.security;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CPrivilegeBuilder;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes promotion and sync privileges for all existing repositories
 * by persisting them through SecurityConfigurationManager.
 *
 * Unlike SecurityContributor which creates read-only in-memory privileges,
 * this creates persistent privileges that can be assigned to roles.
 */
@Named
@Singleton
public class PrivilegeInitializer
{
  private static final Logger log = LoggerFactory.getLogger(PrivilegeInitializer.class);

  private final Provider<RepositoryManager> repositoryManagerProvider;
  private final SecurityConfigurationManager securityConfigManager;

  private volatile boolean initialized = false;

  @Inject
  public PrivilegeInitializer(final Provider<RepositoryManager> repositoryManagerProvider,
                               final SecurityConfigurationManager securityConfigManager)
  {
    this.repositoryManagerProvider = repositoryManagerProvider;
    this.securityConfigManager = securityConfigManager;
  }

  /**
   * Initialize privileges for all repositories.
   * Called lazily on first access to ensure all beans are ready.
   */
  public synchronized void ensureInitialized()
  {
    if (initialized) {
      return;
    }
    initialized = true;

    try {
      // Create wildcard promotion privilege if not exists
      createPrivilegeIfMissing(
          "nexus-artifacts-promotion-promote",
          "nx-artifacts-promotion-all",
          "Full promotion permission for all repositories",
          "nexus-artifacts-promotion",
          "promote"
      );

      // Create wildcard sync privilege if not exists
      createPrivilegeIfMissing(
          "nexus-artifacts-sync-sync",
          "nx-artifacts-sync-all",
          "Full sync permission for all repositories",
          "nexus-artifacts-sync",
          "sync"
      );

      // Create per-repository privileges
      RepositoryManager repoManager = repositoryManagerProvider.get();
      if (repoManager == null) {
        log.warn("RepositoryManager not available, skipping per-repository privileges");
        return;
      }

      int count = 0;
      for (Repository repo : repoManager.browse()) {
        try {
          String repoName = repo.getName();
          String format = repo.getFormat().getValue();

          // Create promotion privilege
          String promoDomain = "nexus-artifacts-promotion-" + sanitize(repoName) + "-" + sanitize(format);
          String promoId = promoDomain + "-promote";
          createPrivilegeIfMissing(
              promoId,
              "Deliver to " + repoName + " (" + format + ")",
              "Permission to deliver artifacts to repository " + repoName + " with format " + format,
              promoDomain,
              "promote"
          );

          // Create sync privilege
          String syncDomain = "nexus-artifacts-sync-" + sanitize(repoName) + "-" + sanitize(format);
          String syncId = syncDomain + "-sync";
          createPrivilegeIfMissing(
              syncId,
              "Sync " + repoName + " (" + format + ")",
              "Permission to sync artifacts from remote repository " + repoName + " with format " + format,
              syncDomain,
              "sync"
          );

          count++;
        }
        catch (Exception e) {
          log.debug("Failed to create privilege for repository: {}", e.getMessage());
        }
      }
      log.info("PrivilegeInitializer created privileges for {} repositories", count);
    }
    catch (Exception e) {
      log.warn("PrivilegeInitializer failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Create a privilege if it does not already exist.
   */
  private void createPrivilegeIfMissing(final String id,
                                         final String name,
                                         final String description,
                                         final String domain,
                                         final String actions)
  {
    try {
      // Check if privilege already exists
      CPrivilege existing = securityConfigManager.readPrivilege(id);
      if (existing != null) {
        log.debug("Privilege already exists: {}", id);
        return;
      }
    }
    catch (Exception e) {
      // readPrivilege may throw if not found, which is expected
      log.debug("Privilege {} not found, will create it", id);
    }

    try {
      CPrivilege privilege = new CPrivilegeBuilder()
          .type("application")
          .id(id)
          .property("domain", domain)
          .property("actions", actions)
          .create();
      privilege.setName(name);
      privilege.setDescription(description);
      privilege.setReadOnly(false);

      securityConfigManager.createPrivilege(privilege);
      log.info("Created privilege: id={}, name={}", id, name);
    }
    catch (Exception e) {
      log.debug("Could not create privilege {}: {}", id, e.getMessage());
    }
  }

  /**
   * Create a promotion privilege for a new repository.
   * Called by RepositoryPrivilegeListener when a repository is created.
   */
  public void createPromotionPrivilege(final String repositoryName, final String format)
  {
    ensureInitialized();
    String domain = "nexus-artifacts-promotion-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-promote";
    createPrivilegeIfMissing(
        id,
        "Deliver to " + repositoryName + " (" + format + ")",
        "Permission to deliver artifacts to repository " + repositoryName + " with format " + format,
        domain,
        "promote"
    );
  }

  /**
   * Create a sync privilege for a new repository.
   * Called by RepositoryPrivilegeListener when a repository is created.
   */
  public void createSyncPrivilege(final String repositoryName, final String format)
  {
    ensureInitialized();
    String domain = "nexus-artifacts-sync-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-sync";
    createPrivilegeIfMissing(
        id,
        "Sync " + repositoryName + " (" + format + ")",
        "Permission to sync artifacts from remote repository " + repositoryName + " with format " + format,
        domain,
        "sync"
    );
  }

  /**
   * Delete a promotion privilege for a repository.
   */
  public void deletePromotionPrivilege(final String repositoryName, final String format)
  {
    String domain = "nexus-artifacts-promotion-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-promote";
    try {
      securityConfigManager.deletePrivilege(id);
      log.info("Deleted promotion privilege: {}", id);
    }
    catch (Exception e) {
      log.debug("Could not delete privilege {}: {}", id, e.getMessage());
    }
  }

  /**
   * Delete a sync privilege for a repository.
   */
  public void deleteSyncPrivilege(final String repositoryName, final String format)
  {
    String domain = "nexus-artifacts-sync-" + sanitize(repositoryName) + "-" + sanitize(format);
    String id = domain + "-sync";
    try {
      securityConfigManager.deletePrivilege(id);
      log.info("Deleted sync privilege: {}", id);
    }
    catch (Exception e) {
      log.debug("Could not delete privilege {}: {}", id, e.getMessage());
    }
  }

  private static String sanitize(final String input) {
    if (input == null || input.isEmpty()) {
      return "_";
    }
    return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
