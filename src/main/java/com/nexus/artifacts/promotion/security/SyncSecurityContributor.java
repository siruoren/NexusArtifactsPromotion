package com.nexus.artifacts.promotion.security;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security contributor that automatically creates sync privilege entries
 * for all existing repositories.
 */
@Named
@Singleton
public class SyncSecurityContributor
    implements SecurityContributor
{
  private static final Logger log = LoggerFactory.getLogger(SyncSecurityContributor.class);

  private final RepositoryManager repositoryManager;

  @Inject
  public SyncSecurityContributor(final RepositoryManager repositoryManager) {
    this.repositoryManager = repositoryManager;
  }

  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    try {
      // Create wildcard sync privilege (all repositories)
      CPrivilege wildcardPrivilege = createSyncPrivilege("*", "*");
      config.addPrivilege(wildcardPrivilege);

      // Create per-repository sync privileges
      int count = 0;
      for (Repository repo : repositoryManager.browse()) {
        String repoName = repo.getName();
        String format = repo.getFormat().getValue();
        CPrivilege repoPrivilege = createSyncPrivilege(repoName, format);
        config.addPrivilege(repoPrivilege);
        count++;
        log.debug("Created sync privilege for repository: {} (format: {})", repoName, format);
      }

      log.info("Sync security contributor created privileges for {} repositories", count);
    }
    catch (Exception e) {
      log.warn("Failed to create sync privileges: {}", e.getMessage(), e);
    }

    return config;
  }

  /**
   * Create a sync privilege for a specific repository and format.
   */
  public static CPrivilege createSyncPrivilege(final String repositoryName, final String format) {
    String id = SyncPrivilegeDescriptor.ID + "-" + repositoryName + "-" + format;
    String name = "Sync from " + repositoryName + " (" + format + ")";
    String description = "Permission to sync artifacts from remote repository " + repositoryName + " with format " + format;

    MemorySecurityConfiguration tempConfig = new MemorySecurityConfiguration();
    CPrivilege privilege = tempConfig.newPrivilege();

    privilege.setId(id);
    privilege.setType(SyncPrivilegeDescriptor.PRIVILEGE_TYPE);
    privilege.setName(name);
    privilege.setDescription(description);

    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("domain", SyncPrivilegeDescriptor.PERMISSION_DOMAIN);
    properties.put("repository", repositoryName);
    properties.put("format", format);
    properties.put("actions", SyncPrivilegeDescriptor.ACTION_SYNC);
    privilege.setProperties(properties);

    return privilege;
  }

  /**
   * Get the privilege ID for a specific repository and format.
   */
  public static String getPrivilegeId(final String repositoryName, final String format) {
    return SyncPrivilegeDescriptor.ID + "-" + repositoryName + "-" + format;
  }
}
