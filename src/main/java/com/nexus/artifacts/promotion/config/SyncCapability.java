package com.nexus.artifacts.promotion.config;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.capability.CapabilitySupport;

import com.nexus.artifacts.promotion.service.SyncService;
import com.nexus.artifacts.promotion.service.TaskExecutorService;

import static com.nexus.artifacts.promotion.config.SyncCapabilityDescriptor.*;

/**
 * Sync management capability for Nexus system settings.
 * Allows administrators to configure:
 * - Sync thread pool size
 * - Max sync queue size
 */
@Named(SyncCapabilityDescriptor.TYPE_ID)
public class SyncCapability extends CapabilitySupport<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(SyncCapability.class);

  private final TaskExecutorService taskExecutor;
  private final SyncService syncService;

  @Inject
  public SyncCapability(final TaskExecutorService taskExecutor, final SyncService syncService) {
    this.taskExecutor = taskExecutor;
    this.syncService = syncService;
  }

  @Override
  protected Map<String, String> createConfig(final Map<String, String> properties) throws Exception {
    return properties;
  }

  @Override
  protected void onActivate(final Map<String, String> props) throws Exception {
    String poolSizeStr = props.get(PROP_SYNC_POOL_SIZE);
    if (poolSizeStr != null) {
      try {
        int poolSize = Integer.parseInt(poolSizeStr);
        taskExecutor.updateSyncPoolSize(poolSize);
        log.info("Sync pool size set to {} from capability configuration", poolSize);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid sync pool size value: {}", poolSizeStr);
      }
    }

    String maxQueueStr = props.get(PROP_MAX_SYNC_QUEUE_SIZE);
    if (maxQueueStr != null) {
      try {
        int maxQueue = Integer.parseInt(maxQueueStr);
        taskExecutor.updateMaxSyncQueueSize(maxQueue);
        log.info("Max sync queue size set to {} from capability configuration", maxQueue);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid max sync queue size value: {}", maxQueueStr);
      }
    }

    String maxRecordsStr = props.get(PROP_MAX_SYNC_RECORDS);
    if (maxRecordsStr != null) {
      try {
        int maxRecords = Integer.parseInt(maxRecordsStr);
        taskExecutor.updateMaxSyncRecords(maxRecords);
        log.info("Max sync records set to {} from capability configuration", maxRecords);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid max sync records value: {}", maxRecordsStr);
      }
    }

    // Update admin credentials for internal API calls
    String adminUser = props.get(PROP_ADMIN_USERNAME);
    String adminPass = props.get(PROP_ADMIN_PASSWORD);
    if (adminUser != null || adminPass != null) {
      syncService.updateAdminCredentials(adminUser, adminPass);
      log.info("Admin credentials updated from capability configuration");
    }

    log.info("Sync capability activated");
  }

  @Override
  protected void onUpdate(final Map<String, String> props) throws Exception {
    onActivate(props);
  }

  @Override
  protected void onPassivate(final Map<String, String> props) throws Exception {
    log.info("Sync capability passivated");
  }
}
