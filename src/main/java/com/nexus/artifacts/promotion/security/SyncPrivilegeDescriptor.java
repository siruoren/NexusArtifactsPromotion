package com.nexus.artifacts.promotion.security;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;

import com.google.common.base.Strings;

/**
 * Privilege descriptor for remote repository sync.
 * Allows administrators to grant per-repository sync permissions to users/roles.
 *
 * Extends ApplicationPrivilegeDescriptor which uses the standard
 * "application" privilege type with domain and actions.
 */
@Named(SyncPrivilegeDescriptor.ID)
@Singleton
public class SyncPrivilegeDescriptor extends ApplicationPrivilegeDescriptor {

  public static final String ID = "nexus-artifacts-sync";

  public static final String PRIVILEGE_TYPE = ApplicationPrivilegeDescriptor.TYPE;

  public static final String PERMISSION_DOMAIN = "nexus-artifacts-sync";

  public static final String ACTION_SYNC = "sync";

  public SyncPrivilegeDescriptor() {
    super();
  }

  /**
   * Create a permission string for sync privilege.
   */
  public static String createPermissionString(final String repositoryName, final String format) {
    StringBuilder sb = new StringBuilder();
    sb.append(PERMISSION_DOMAIN);
    if (!Strings.isNullOrEmpty(repositoryName)) {
      sb.append(":").append(sanitize(repositoryName));
    }
    if (!Strings.isNullOrEmpty(format)) {
      sb.append(":").append(sanitize(format));
    }
    return sb.toString();
  }

  private static String sanitize(final String input) {
    if (input == null) {
      return "";
    }
    return input.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
  }
}
