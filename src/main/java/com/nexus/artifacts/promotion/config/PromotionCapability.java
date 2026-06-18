package com.nexus.artifacts.promotion.config;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.capability.CapabilitySupport;

import com.nexus.artifacts.promotion.service.DockerService;
import com.nexus.artifacts.promotion.service.NexusApiConfigService;
import com.nexus.artifacts.promotion.service.RetryableOperation;
import com.nexus.artifacts.promotion.service.TaskExecutorService;

import static com.nexus.artifacts.promotion.config.PromotionCapabilityDescriptor.*;

/**
 * Promotion management capability for Nexus system settings.
 * Allows administrators to configure:
 * - Promotion thread pool size
 * - Docker release repositories (filter snapshot tags when promoting to release repos)
 */
@Named(PromotionCapabilityDescriptor.TYPE_ID)
public class PromotionCapability extends CapabilitySupport<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(PromotionCapability.class);

  private final TaskExecutorService taskExecutor;
  private final DockerService dockerService;
  private final NexusApiConfigService apiConfigService;

  @Inject
  public PromotionCapability(final TaskExecutorService taskExecutor, final DockerService dockerService,
                              final NexusApiConfigService apiConfigService) {
    this.taskExecutor = taskExecutor;
    this.dockerService = dockerService;
    this.apiConfigService = apiConfigService;
  }

  @Override
  protected Map<String, String> createConfig(final Map<String, String> properties) throws Exception {
    return properties;
  }

  @Override
  protected void onActivate(final Map<String, String> props) throws Exception {
    String poolSizeStr = props.get(PROP_PROMOTION_POOL_SIZE);
    if (poolSizeStr != null) {
      try {
        int poolSize = Integer.parseInt(poolSizeStr);
        taskExecutor.updatePromotionPoolSize(poolSize);
        log.info("Promotion pool size set to {} from capability configuration", poolSize);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid promotion pool size value: {}", poolSizeStr);
      }
    }

    String maxQueueStr = props.get(PROP_MAX_PROMOTION_QUEUE_SIZE);
    if (maxQueueStr != null) {
      try {
        int maxQueue = Integer.parseInt(maxQueueStr);
        taskExecutor.updateMaxPromotionQueueSize(maxQueue);
        log.info("Max promotion queue size set to {} from capability configuration", maxQueue);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid max promotion queue size value: {}", maxQueueStr);
      }
    }

    // Configure retry strategy
    long retryBaseDelay = 1000;
    long retryMaxDelay = 30000;

    String retryBaseDelayStr = props.get(PROP_RETRY_BASE_DELAY_MS);
    if (retryBaseDelayStr != null) {
      try {
        retryBaseDelay = Long.parseLong(retryBaseDelayStr);
        log.info("Retry base delay set to {}ms from capability configuration", retryBaseDelay);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid retry base delay value: {}", retryBaseDelayStr);
      }
    }

    String retryMaxDelayStr = props.get(PROP_RETRY_MAX_DELAY_MS);
    if (retryMaxDelayStr != null) {
      try {
        retryMaxDelay = Long.parseLong(retryMaxDelayStr);
        log.info("Retry max delay set to {}ms from capability configuration", retryMaxDelay);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid retry max delay value: {}", retryMaxDelayStr);
      }
    }

    RetryableOperation.configure(retryBaseDelay, retryMaxDelay, 500);

    // Update Docker release repositories configuration
    String dockerReleaseRepos = props.get(PROP_DOCKER_RELEASE_REPOS);
    if (dockerReleaseRepos != null && !dockerReleaseRepos.trim().isEmpty()) {
      dockerService.updateDockerReleaseRepos(dockerReleaseRepos.trim());
      log.info("Docker release repositories set to: {}", dockerReleaseRepos.trim());
    }
    else {
      dockerService.updateDockerReleaseRepos("");
      log.info("Docker release repositories cleared");
    }

    // Configure API page size
    String apiPageSizeStr = props.get(PROP_API_PAGE_SIZE);
    if (apiPageSizeStr != null) {
      try {
        int apiPageSize = Integer.parseInt(apiPageSizeStr);
        apiConfigService.updateApiPageSize(apiPageSize);
        log.info("API page size set to {} from capability configuration", apiPageSize);
      }
      catch (NumberFormatException e) {
        log.warn("Invalid API page size value: {}", apiPageSizeStr);
      }
      catch (IllegalArgumentException e) {
        log.warn("API page size out of range: {}", e.getMessage());
      }
    }

    // Configure sort field
    String sortField = props.get(PROP_SORT_FIELD);
    if (sortField != null && !sortField.trim().isEmpty()) {
      apiConfigService.updateSortField(sortField.trim());
      log.info("Sort field set to '{}' from capability configuration", sortField.trim());
    }

    // Configure sort direction
    String sortDirection = props.get(PROP_SORT_DIRECTION);
    if (sortDirection != null && !sortDirection.trim().isEmpty()) {
      try {
        apiConfigService.updateSortDirection(sortDirection.trim());
        log.info("Sort direction set to '{}' from capability configuration", sortDirection.trim());
      }
      catch (IllegalArgumentException e) {
        log.warn("Invalid sort direction value: {}", sortDirection.trim());
      }
    }

    log.info("Promotion capability activated");
  }

  @Override
  protected void onUpdate(final Map<String, String> props) throws Exception {
    onActivate(props);
  }

  @Override
  protected void onPassivate(final Map<String, String> props) throws Exception {
    log.info("Promotion capability passivated");
  }
}
