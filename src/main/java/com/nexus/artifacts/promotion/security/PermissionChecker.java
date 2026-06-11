package com.nexus.artifacts.promotion.security;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enterprise-grade permission checker for promotion and sync operations.
 * Validates user permissions at both UI and API levels.
 *
 * Permission format follows Shiro WildcardPermission:
 *   domain:action
 *
 * Wildcard: nexus-artifacts-promotion:promote  (all repos)
 * Specific: nexus-artifacts-promotion-{repoName}-{format}:promote
 */
@Named
@Singleton
public class PermissionChecker {

  private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

  @Inject
  public PermissionChecker() {
  }

  /**
   * Check if the current user has promotion permission for the given repository.
   */
  public boolean hasPromotionPermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      log.warn("Promotion permission check failed: no authenticated subject");
      return false;
    }

    // Check specific repository permission first
    // domain = "nexus-artifacts-promotion-{repoName}-{format}", action = "promote"
    String specificPermission = "nexus-artifacts-promotion-"
        + sanitize(repositoryName) + "-" + sanitize(format)
        + ":" + PromotionPrivilegeDescriptor.ACTION_PROMOTE;

    if (subject.isPermitted(specificPermission)) {
      log.debug("User '{}' has specific promotion permission for repo '{}', format '{}'",
          subject.getPrincipal(), repositoryName, format);
      return true;
    }

    // Check wildcard permission: "nexus-artifacts-promotion:promote"
    String wildcardPermission = "nexus-artifacts-promotion:" + PromotionPrivilegeDescriptor.ACTION_PROMOTE;
    if (subject.isPermitted(wildcardPermission)) {
      log.debug("User '{}' has wildcard promotion permission", subject.getPrincipal());
      return true;
    }

    // Also check the old wildcard format for backward compatibility
    String oldWildcardPermission = "nexus-artifacts-promotion:*";
    if (subject.isPermitted(oldWildcardPermission)) {
      log.debug("User '{}' has old wildcard promotion permission", subject.getPrincipal());
      return true;
    }

    log.warn("User '{}' lacks promotion permission for repo '{}', format '{}'",
        subject.getPrincipal(), repositoryName, format);
    return false;
  }

  /**
   * Check if the current user has sync permission for the given repository.
   */
  public boolean hasSyncPermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      log.warn("Sync permission check failed: no authenticated subject");
      return false;
    }

    // Check specific repository permission
    String specificPermission = "nexus-artifacts-sync-"
        + sanitize(repositoryName) + "-" + sanitize(format)
        + ":" + SyncPrivilegeDescriptor.ACTION_SYNC;

    if (subject.isPermitted(specificPermission)) {
      log.debug("User '{}' has specific sync permission for repo '{}', format '{}'",
          subject.getPrincipal(), repositoryName, format);
      return true;
    }

    // Check wildcard permission
    String wildcardPermission = "nexus-artifacts-sync:" + SyncPrivilegeDescriptor.ACTION_SYNC;
    if (subject.isPermitted(wildcardPermission)) {
      log.debug("User '{}' has wildcard sync permission", subject.getPrincipal());
      return true;
    }

    String oldWildcardPermission = "nexus-artifacts-sync:*";
    if (subject.isPermitted(oldWildcardPermission)) {
      log.debug("User '{}' has old wildcard sync permission", subject.getPrincipal());
      return true;
    }

    log.warn("User '{}' lacks sync permission for repo '{}', format '{}'",
        subject.getPrincipal(), repositoryName, format);
    return false;
  }

  /**
   * Check if the current user has promotion permission for a target repository.
   */
  public boolean hasPromotionPermissionForTarget(final String sourceRepository,
                                                  final String targetRepository,
                                                  final String format)
  {
    return hasPromotionPermission(sourceRepository, format)
        && hasPromotionPermission(targetRepository, format);
  }

  /**
   * Assert promotion permission, throwing exception if not authorized.
   */
  public void checkPromotionPermission(final String repositoryName, final String format) {
    if (!hasPromotionPermission(repositoryName, format)) {
      throw new SecurityException(
          "User does not have promotion permission for repository: " + repositoryName);
    }
  }

  /**
   * Assert promotion permission for both source and target.
   */
  public void checkPromotionPermissionForTarget(final String sourceRepository,
                                                  final String targetRepository,
                                                  final String format)
  {
    if (!hasPromotionPermissionForTarget(sourceRepository, targetRepository, format)) {
      throw new SecurityException(
          "User does not have promotion permission for repositories: " + sourceRepository + " -> " + targetRepository);
    }
  }

  /**
   * Assert sync permission, throwing exception if not authorized.
   */
  public void checkSyncPermission(final String repositoryName, final String format) {
    if (!hasSyncPermission(repositoryName, format)) {
      throw new SecurityException(
          "User does not have sync permission for repository: " + repositoryName);
    }
  }

  /**
   * Get the current authenticated username.
   */
  public String getCurrentUsername() {
    Subject subject = SecurityUtils.getSubject();
    if (subject != null && subject.isAuthenticated() && subject.getPrincipal() != null) {
      return subject.getPrincipal().toString();
    }
    return "anonymous";
  }

  private String sanitize(final String input) {
    if (input == null || input.isEmpty()) {
      return "*";
    }
    return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
