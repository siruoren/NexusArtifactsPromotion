package com.nexus.artifacts.promotion.task;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.formfields.CheckboxFormField;
import org.sonatype.nexus.formfields.StringTextFormField;
import org.sonatype.nexus.scheduling.TaskDescriptorSupport;

/**
 * Task descriptor for scheduled proxy repository sync.
 * Registers as a task template in Nexus Tasks UI with:
 * - Text field to input proxy repository name
 * - Text field to input sync path (empty = full repository sync)
 * - Checkbox to select incremental sync mode (MD5 comparison)
 *
 * After creating the task, users can configure it in Nexus's
 * System → Tasks UI with a cron schedule or manual execution.
 */
@Named(ProxySyncTaskDescriptor.TYPE_ID)
@Singleton
public class ProxySyncTaskDescriptor
    extends TaskDescriptorSupport
{
  public static final String TYPE_ID = "proxy-repository-sync";

  public static final String PROP_REPOSITORY_NAME = "repositoryName";

  public static final String PROP_SYNC_PATH = "syncPath";

  public static final String PROP_INCREMENTAL = "incremental";

  private static final StringTextFormField REPOSITORY_FIELD = new StringTextFormField(
      PROP_REPOSITORY_NAME,
      "Repository Name",
      "Name of the proxy repository to sync (must be a proxy type repository)",
      true);

  private static final StringTextFormField SYNC_PATH_FIELD = new StringTextFormField(
      PROP_SYNC_PATH,
      "Sync Path",
      "Directory path to sync (leave empty for full repository sync). Example: com/example/mypackage",
      false);

  private static final CheckboxFormField INCREMENTAL_FIELD = new CheckboxFormField(
      PROP_INCREMENTAL,
      "Incremental Sync",
      "If checked, only sync assets whose MD5 differs from local cache (faster). If unchecked, sync all assets regardless of local cache state (full sync).",
      false);

  public ProxySyncTaskDescriptor() {
    super(TYPE_ID,
        ProxySyncTask.class,
        "Proxy Repository Scheduled Sync",
        VISIBLE,
        EXPOSED,
        REPOSITORY_FIELD,
        SYNC_PATH_FIELD,
        INCREMENTAL_FIELD);
  }
}
