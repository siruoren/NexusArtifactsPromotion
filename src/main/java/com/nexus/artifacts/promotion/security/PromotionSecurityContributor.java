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
 * Security contributor that automatically creates promotion privilege entries
 * for all existing repositories.
 *
 * When a new repository is created in Nexus, a corresponding promotion privilege
 * entry is automatically added so administrators can assign it to roles.
 */
@Named
@Singleton
public class PromotionSecurityContributor
    implements SecurityContributor
{
  private static final Logger log = LoggerFactory.getLogger(PromotionSecurityContributor.class);

  private final RepositoryManager repositoryManager;

  @Inject
  public PromotionSecurityContributor(final RepositoryManager repositoryManager) {
    this.repositoryManager = repositoryManager;
  }

  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    try {
      // Create wildcard promotion privilege (all repositories)
      CPrivilege wildcardPrivilege = createPromotionPrivilege("*", "*");
      config.addPrivilege(wildcardPrivilege);

      // Create per-repository promotion privileges
      int count = 0;
      for (Repository repo : repositoryManager.browse()) {
        String repoName = repo.getName();
        String format = repo.getFormat().getValue();
        CPrivilege repoPrivilege = createPromotionPrivilege(repoName, format);
        config.addPrivilege(repoPrivilege);
        count++;
        log.debug("Created promotion privilege for repository: {} (format: {})", repoName, format);
      }

      log.info("Promotion security contributor created privileges for {} repositories", count);
    }
    catch (Exception e) {
      log.warn("Failed to create promotion privileges: {}", e.getMessage(), e);
    }

    return config;
  }

  /**
   * Create a promotion privilege for a specific repository and format.
   */
  public static CPrivilege createPromotionPrivilege(final String repositoryName, final String format) {
    String id = PromotionPrivilegeDescriptor.ID + "-" + repositoryName + "-" + format;
    String name = "Promote from " + repositoryName + " (" + format + ")";
    String description = "Permission to promote artifacts from repository " + repositoryName + " with format " + format;

    MemorySecurityConfiguration tempConfig = new MemorySecurityConfiguration();
    CPrivilege privilege = tempConfig.newPrivilege();

    privilege.setId(id);
    privilege.setType(PromotionPrivilegeDescriptor.PRIVILEGE_TYPE);
    privilege.setName(name);
    privilege.setDescription(description);

    // Set privilege properties for ApplicationPrivilegeDescriptor
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("domain", PromotionPrivilegeDescriptor.PERMISSION_PREFIX);
    properties.put("repository", repositoryName);
    properties.put("format", format);
    properties.put("actions", PromotionPrivilegeDescriptor.ACTION_PROMOTE);
    privilege.setProperties(properties);

    return privilege;
  }

  /**
   * Get the privilege ID for a specific repository and format.
   */
  public static String getPrivilegeId(final String repositoryName, final String format) {
    return PromotionPrivilegeDescriptor.ID + "-" + repositoryName + "-" + format;
  }
}
