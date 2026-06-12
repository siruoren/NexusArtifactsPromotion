package com.nexus.artifacts.promotion.security;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import com.nexus.artifacts.promotion.exception.PermissionDeniedException;

/**
 * Permission checker using Nexus system permissions.
 * Uses Nexus built-in repository-view permissions instead of custom privileges.
 *
 * Nexus system permission format:
 *   nexus:repository-view:{format}:{repositoryName}:edit  (write)
 *   nexus:repository-view:{format}:{repositoryName}:read  (read)
 */
@Named
@Singleton
public class PermissionChecker {

  private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

  private final RepositoryManager repositoryManager;

  @Inject
  public PermissionChecker(final RepositoryManager repositoryManager) {
    this.repositoryManager = repositoryManager;
  }

  /**
   * Check if the current user has write (edit) permission on a repository.
   * Reads the format from the repository directly, avoiding frontend format mismatch.
   */
  public boolean hasRepositoryWritePermission(final String repositoryName) {
    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      log.warn("Repository not found for write permission check: {}", repositoryName);
      return false;
    }
    return hasRepositoryWritePermission(repositoryName, repo.getFormat().getValue());
  }

  /**
   * Check if the current user has write (edit) permission on a repository.
   * Uses Nexus system permission: nexus:repository-view:{format}:{repoName}:edit
   */
  public boolean hasRepositoryWritePermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      log.warn("Write permission check failed: no authenticated subject");
      return false;
    }

    String fmt = (format != null && !format.isEmpty()) ? format : "raw";

    // Check specific repository write permission
    String permission = "nexus:repository-view:" + fmt + ":" + repositoryName + ":edit";
    if (subject.isPermitted(permission)) {
      log.debug("User '{}' has write permission for repo '{}', format '{}'",
          subject.getPrincipal(), repositoryName, fmt);
      return true;
    }

    log.debug("User '{}' lacks write permission for repo '{}', format '{}', tried: {}",
        subject.getPrincipal(), repositoryName, fmt, permission);
    return false;
  }

  /**
   * Check if the current user has read permission on a repository.
   * Uses Nexus system permission: nexus:repository-view:{format}:{repoName}:read
   */
  public boolean hasRepositoryReadPermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      return false;
    }

    String fmt = (format != null && !format.isEmpty()) ? format : "raw";
    String permission = "nexus:repository-view:" + fmt + ":" + repositoryName + ":read";
    return subject.isPermitted(permission);
  }

  /**
   * Check if the current user has promotion permission for a source repository.
   * For source repo, we only need read permission (user can see the asset).
   */
  public boolean hasPromotionPermission(final String repositoryName, final String format) {
    return hasRepositoryReadPermission(repositoryName, format);
  }

  /**
   * Check if the current user has promotion permission for a target repository.
   * For target repo, we need write (edit) permission.
   */
  public boolean hasPromotionPermissionForTarget(final String sourceRepository,
                                                  final String targetRepository,
                                                  final String format)
  {
    return hasRepositoryReadPermission(sourceRepository, format)
        && hasRepositoryWritePermission(targetRepository, format);
  }

  /**
   * Assert write permission on target repository, throwing exception if not authorized.
   * Reads the format from the repository directly.
   */
  public void checkTargetWritePermission(final String targetRepository) {
    if (!hasRepositoryWritePermission(targetRepository)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("edit", username, targetRepository);
    }
  }

  /**
   * Assert write permission on target repository, throwing exception if not authorized.
   * The exception includes username and repository name for UI display.
   */
  public void checkTargetWritePermission(final String targetRepository, final String format) {
    if (!hasRepositoryWritePermission(targetRepository, format)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("edit", username, targetRepository);
    }
  }

  /**
   * Assert promotion permission for source repository (read access).
   */
  public void checkPromotionPermission(final String repositoryName, final String format) {
    if (!hasRepositoryReadPermission(repositoryName, format)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("read", username, repositoryName);
    }
  }

  /**
   * Assert promotion permission for both source and target.
   */
  public void checkPromotionPermissionForTarget(final String sourceRepository,
                                                  final String targetRepository,
                                                  final String format)
  {
    if (!hasRepositoryReadPermission(sourceRepository, format)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("read", username, sourceRepository);
    }
    if (!hasRepositoryWritePermission(targetRepository, format)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("edit", username, targetRepository);
    }
  }

  /**
   * Check if the current user has sync permission for the given repository.
   * Sync requires edit permission on the repository.
   */
  public boolean hasSyncPermission(final String repositoryName, final String format) {
    return hasRepositoryWritePermission(repositoryName, format);
  }

  /**
   * Assert sync permission, throwing exception if not authorized.
   */
  public void checkSyncPermission(final String repositoryName, final String format) {
    if (!hasSyncPermission(repositoryName, format)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("edit", username, repositoryName);
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
}
