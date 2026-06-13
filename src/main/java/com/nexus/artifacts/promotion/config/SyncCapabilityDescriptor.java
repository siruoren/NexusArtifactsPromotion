package com.nexus.artifacts.promotion.config;

import java.util.Collections;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityDescriptorSupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.NumberTextFormField;
import org.sonatype.nexus.formfields.PasswordFormField;
import org.sonatype.nexus.formfields.StringTextFormField;

import com.google.common.collect.Lists;

/**
 * Descriptor for the Sync Management capability.
 * Appears in Nexus System Settings under "Sync Management".
 */
@Named(SyncCapabilityDescriptor.TYPE_ID)
@Singleton
public class SyncCapabilityDescriptor extends CapabilityDescriptorSupport<Object> {

  public static final String TYPE_ID = "artifacts-sync-management";
  public static final CapabilityType CAPABILITY_TYPE = CapabilityType.capabilityType(TYPE_ID);

  public static final String PROP_SYNC_POOL_SIZE = "syncPoolSize";
  public static final String PROP_MAX_SYNC_QUEUE_SIZE = "maxSyncQueueSize";
  public static final String PROP_ADMIN_USERNAME = "adminUsername";
  public static final String PROP_ADMIN_PASSWORD = "adminPassword";

  private static final NumberTextFormField POOL_SIZE_FIELD = new NumberTextFormField(
      PROP_SYNC_POOL_SIZE,
      "Sync Thread Pool Size",
      "Number of threads for concurrent sync tasks (1-50, default: 4)",
      false);

  private static final NumberTextFormField MAX_QUEUE_FIELD = new NumberTextFormField(
      PROP_MAX_SYNC_QUEUE_SIZE,
      "Max Sync Queue Size",
      "Maximum number of concurrent sync queue tasks (1-100, default: 20)",
      false);

  private static final StringTextFormField ADMIN_USERNAME_FIELD = new StringTextFormField(
      PROP_ADMIN_USERNAME,
      "Admin Username",
      "Username for internal API calls (default: admin)",
      false);

  private static final PasswordFormField ADMIN_PASSWORD_FIELD = new PasswordFormField(
      PROP_ADMIN_PASSWORD,
      "Admin Password",
      "Password for internal API calls (default: admin123)",
      false);

  public SyncCapabilityDescriptor() {
  }

  @Override
  public CapabilityType type() {
    return CAPABILITY_TYPE;
  }

  @Override
  public String name() {
    return "Sync Management";
  }

  @Override
  public String about() {
    return "Configure remote repository sync settings including thread pool size and max queue size";
  }

  @Override
  public java.util.List<FormField> formFields() {
    return Lists.newArrayList(POOL_SIZE_FIELD, MAX_QUEUE_FIELD, ADMIN_USERNAME_FIELD, ADMIN_PASSWORD_FIELD);
  }

  public Set<Tag> getTags() {
    return Collections.singleton(new Tag("sync", "artifacts"));
  }
}
