package com.nexus.artifacts.promotion.security;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.privilege.ApplicationPrivilegeDescriptor;

import com.google.common.base.Strings;

/**
 * Privilege descriptor for artifact promotion.
 * Allows administrators to grant per-repository promotion permissions to users/roles.
 *
 * Extends ApplicationPrivilegeDescriptor which uses the standard
 * "application" privilege type with domain and actions.
 */
@Named(PromotionPrivilegeDescriptor.ID)
@Singleton
public class PromotionPrivilegeDescriptor extends ApplicationPrivilegeDescriptor {

  public static final String ID = "nexus-artifacts-promotion";

  public static final String PRIVILEGE_TYPE = ApplicationPrivilegeDescriptor.TYPE;

  public static final String PERMISSION_PREFIX = "nexus:artifacts-promotion";

  public static final String ACTION_PROMOTE = "promote";

  public PromotionPrivilegeDescriptor() {
    super();
  }

  /**
   * Create a permission string for promotion privilege.
   */
  public static String createPermissionString(final String repositoryName, final String format) {
    StringBuilder sb = new StringBuilder();
    sb.append(PERMISSION_PREFIX);
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
