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
 * Prevents privilege escalation and ensures consistent security enforcement.
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
   *
   * @param repositoryName the source repository name
   * @param format         the repository format (maven2, npm, docker, etc.)
   * @return true if the user has promotion permission
   */
  public boolean hasPromotionPermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      log.warn("Promotion permission check failed: no authenticated subject");
      return false;
    }

    // Check specific repository permission first
    String specificPermission = PromotionPrivilegeDescriptor.PERMISSION_PREFIX
        + ":" + sanitize(repositoryName)
        + ":" + sanitize(format);

    if (subject.isPermitted(specificPermission)) {
      log.debug("User '{}' has specific promotion permission for repo '{}', format '{}'",
          subject.getPrincipal(), repositoryName, format);
      return true;
    }

    // Check wildcard permission
    String wildcardPermission = PromotionPrivilegeDescriptor.PERMISSION_PREFIX + ":*";
    if (subject.isPermitted(wildcardPermission)) {
      log.debug("User '{}' has wildcard promotion permission", subject.getPrincipal());
      return true;
    }

    log.warn("User '{}' lacks promotion permission for repo '{}', format '{}'",
        subject.getPrincipal(), repositoryName, format);
    return false;
  }

  /**
   * Check if the current user has sync permission for the given repository.
   *
   * @param repositoryName the remote repository name
   * @param format         the repository format
   * @return true if the user has sync permission
   */
  public boolean hasSyncPermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      log.warn("Sync permission check failed: no authenticated subject");
      return false;
    }

    String specificPermission = SyncPrivilegeDescriptor.PERMISSION_DOMAIN
        + ":" + sanitize(repositoryName)
        + ":" + sanitize(format);

    if (subject.isPermitted(specificPermission)) {
      log.debug("User '{}' has specific sync permission for repo '{}', format '{}'",
          subject.getPrincipal(), repositoryName, format);
      return true;
    }

    String wildcardPermission = SyncPrivilegeDescriptor.PERMISSION_DOMAIN + ":*";
    if (subject.isPermitted(wildcardPermission)) {
      log.debug("User '{}' has wildcard sync permission", subject.getPrincipal());
      return true;
    }

    log.warn("User '{}' lacks sync permission for repo '{}', format '{}'",
        subject.getPrincipal(), repositoryName, format);
    return false;
  }

  /**
   * Check if the current user has promotion permission for a target repository.
   *
   * @param sourceRepository the source repository name
   * @param targetRepository the target repository name
   * @param format           the repository format
   * @return true if the user has promotion permission for both source and target
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
   * Assert promotion permission for both source and target, throwing exception if not authorized.
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

  /**
   * Sanitize input to prevent injection attacks.
   */
  private String sanitize(final String input) {
    if (input == null || input.isEmpty()) {
      return "*";
    }
    // Remove any characters that could be used for injection
    return input.replaceAll("[^a-zA-Z0-9_\\-.:/]", "_");
  }
}
