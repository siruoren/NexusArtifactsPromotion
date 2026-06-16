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
  public static final String PROP_MAX_SYNC_RECORDS = "maxSyncRecords";
  public static final String PROP_DOCKER_RELEASE_PROXY_REPOS = "dockerReleaseProxyRepos";

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

  private static final NumberTextFormField MAX_RECORDS_FIELD = new NumberTextFormField(
      PROP_MAX_SYNC_RECORDS,
      "Max Sync Queue Records",
      "Maximum number of sync task records to retain (1-10000, default: 200)",
      false);

  private static final StringTextFormField DOCKER_RELEASE_PROXY_REPOS_FIELD = new StringTextFormField(
      PROP_DOCKER_RELEASE_PROXY_REPOS,
      "Docker Release Proxy Repositories",
      "Comma-separated list of Docker proxy repository names that are release repositories. "
          + "When syncing images from a release proxy repository, only release tags are synced "
          + "(tags containing SNAPSHOT/dev/alpha/beta/RC are filtered out). Example: docker-release-proxy,prod-docker-proxy",
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
    return "Configure remote repository sync settings including thread pool size, max queue size, and Docker release proxy repos";
  }

  @Override
  public java.util.List<FormField> formFields() {
    return Lists.newArrayList(POOL_SIZE_FIELD, MAX_QUEUE_FIELD, MAX_RECORDS_FIELD, DOCKER_RELEASE_PROXY_REPOS_FIELD);
  }

  public Set<Tag> getTags() {
    return Collections.singleton(new Tag("sync", "artifacts"));
  }
}
