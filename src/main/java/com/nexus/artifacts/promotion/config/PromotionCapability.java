package com.nexus.artifacts.promotion.config;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.capability.CapabilitySupport;

import com.nexus.artifacts.promotion.service.TaskExecutorService;

import static com.nexus.artifacts.promotion.config.PromotionCapabilityDescriptor.*;

/**
 * Promotion management capability for Nexus system settings.
 * Allows administrators to configure:
 * - Promotion thread pool size
 */
@Named(PromotionCapabilityDescriptor.TYPE_ID)
public class PromotionCapability extends CapabilitySupport<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(PromotionCapability.class);

  private final TaskExecutorService taskExecutor;

  @Inject
  public PromotionCapability(final TaskExecutorService taskExecutor) {
    this.taskExecutor = taskExecutor;
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
