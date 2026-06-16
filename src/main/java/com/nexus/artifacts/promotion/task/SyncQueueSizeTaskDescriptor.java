package com.nexus.artifacts.promotion.task;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.formfields.NumberTextFormField;
import org.sonatype.nexus.scheduling.TaskDescriptorSupport;

/**
 * Task descriptor for updating sync queue concurrency limit.
 * Registers as a task template in Nexus Tasks UI.
 */
@Named(SyncQueueSizeTaskDescriptor.TYPE_ID)
@Singleton
public class SyncQueueSizeTaskDescriptor
    extends TaskDescriptorSupport
{
  public static final String TYPE_ID = "sync-queue-size-update";

  public static final String PROP_MAX_QUEUE_SIZE = "maxSyncQueueSize";

  private static final NumberTextFormField MAX_QUEUE_FIELD = new NumberTextFormField(
      PROP_MAX_QUEUE_SIZE,
      "Max Sync Queue Size",
      "Maximum number of concurrent sync queue tasks (1-100, default: 20)",
      true);

  public SyncQueueSizeTaskDescriptor() {
    super(TYPE_ID,
        SyncQueueSizeTask.class,
        "Update Sync Queue Concurrency",
        VISIBLE,
        EXPOSED,
        MAX_QUEUE_FIELD);
  }
}
