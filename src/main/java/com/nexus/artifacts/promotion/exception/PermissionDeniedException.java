package com.nexus.artifacts.promotion.exception;

/**
 * Exception thrown when a user lacks the required permission.
 */
public class PermissionDeniedException extends RuntimeException {

  private final String permission;
  private final String username;
  private final String repository;

  public PermissionDeniedException(final String permission,
                                    final String username,
                                    final String repository)
  {
    super(String.format("User '%s' does not have permission '%s' for repository '%s'",
        username, permission, repository));
    this.permission = permission;
    this.username = username;
    this.repository = repository;
  }

  public String getPermission() { return permission; }
  public String getUsername() { return username; }
  public String getRepository() { return repository; }
}
