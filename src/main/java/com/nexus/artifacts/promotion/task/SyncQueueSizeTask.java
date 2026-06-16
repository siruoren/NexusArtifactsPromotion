package com.nexus.artifacts.promotion.task;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskSupport;

import com.nexus.artifacts.promotion.service.TaskExecutorService;

/**
 * Task to update the sync queue concurrency limit.
 * Appears in Nexus Tasks UI as a task template.
 */
@Named(SyncQueueSizeTaskDescriptor.TYPE_ID)
public class SyncQueueSizeTask
    extends TaskSupport
{
  public static final String PROP_MAX_QUEUE_SIZE = "maxSyncQueueSize";

  private final TaskExecutorService taskExecutorService;

  @Inject
  public SyncQueueSizeTask(final TaskExecutorService taskExecutorService) {
    this.taskExecutorService = taskExecutorService;
  }

  @Override
  protected Object execute() throws Exception {
    TaskConfiguration config = getConfiguration();
    String sizeStr = config.getString(PROP_MAX_QUEUE_SIZE);
    int newSize = 20; // default
    try {
      newSize = Integer.parseInt(sizeStr);
    }
    catch (NumberFormatException e) {
      log.warn("Invalid maxSyncQueueSize value: {}, using default 20", sizeStr);
    }
    newSize = Math.max(1, Math.min(100, newSize));

    log.info("Updating sync queue max size to {}", newSize);
    taskExecutorService.updateMaxSyncQueueSize(newSize);

    return "Sync queue max size updated to " + newSize;
  }

  @Override
  public String getMessage() {
    return "Updating sync queue concurrency limit";
  }
}
