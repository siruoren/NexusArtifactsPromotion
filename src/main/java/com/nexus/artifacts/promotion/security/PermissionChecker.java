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
   * Assert delete permission on target repository, throwing exception if not authorized.
   * Reads the format from the repository directly.
   */
  public void checkTargetDeletePermission(final String targetRepository) {
    if (!hasRepositoryDeletePermission(targetRepository)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("delete", username, targetRepository);
    }
  }

  /**
   * Assert promotion permission on target repository (both edit and delete).
   * Promotion needs edit to upload files and delete to remove files.
   * Reads the format from the repository directly.
   */
  public void checkTargetPromotionPermission(final String targetRepository) {
    if (!hasRepositoryWritePermission(targetRepository)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("edit", username, targetRepository);
    }
    if (!hasRepositoryDeletePermission(targetRepository)) {
      String username = getCurrentUsername();
      throw new PermissionDeniedException("delete", username, targetRepository);
    }
  }

  /**
   * Check if the current user has sync permission for the given repository.
   * Sync requires edit permission on the repository AND the repository must be a proxy type.
   */
  public boolean hasSyncPermission(final String repositoryName, final String format) {
    if (!isProxyRepository(repositoryName)) {
      log.debug("Sync permission denied: {} is not a proxy repository", repositoryName);
      return false;
    }
    return hasRepositoryWritePermission(repositoryName, format);
  }

  /**
   * Check if a repository is of proxy type.
   */
  public boolean isProxyRepository(final String repositoryName) {
    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      return false;
    }
    return "proxy".equals(repo.getType().getValue());
  }

  /**
   * Check if the current user has delete permission on a repository.
   * Uses Nexus system permission: nexus:repository-view:{format}:{repoName}:delete
   */
  public boolean hasRepositoryDeletePermission(final String repositoryName, final String format) {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated()) {
      return false;
    }
    String fmt = (format != null && !format.isEmpty()) ? format : "raw";
    String permission = "nexus:repository-view:" + fmt + ":" + repositoryName + ":delete";
    return subject.isPermitted(permission);
  }

  /**
   * Check if the current user has delete permission on a repository.
   * Reads the format from the repository directly.
   */
  public boolean hasRepositoryDeletePermission(final String repositoryName) {
    Repository repo = repositoryManager.get(repositoryName);
    if (repo == null) {
      log.warn("Repository not found for delete permission check: {}", repositoryName);
      return false;
    }
    return hasRepositoryDeletePermission(repositoryName, repo.getFormat().getValue());
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
