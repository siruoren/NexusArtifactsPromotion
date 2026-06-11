package com.nexus.artifacts.promotion;

import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;

/**
 * Guice module for Nexus Artifacts Promotion Plugin.
 * All components are auto-discovered via Sisu @Named/@Singleton annotations.
 * This module only performs additional configuration that cannot be done via annotations.
 */
@Named
public class NexusArtifactsPromotionModule extends AbstractModule {

  private static final Logger log = LoggerFactory.getLogger(NexusArtifactsPromotionModule.class);

  @Override
  protected void configure() {
    log.info("NexusArtifactsPromotionModule configured - components auto-discovered via Sisu");
    // All components use @Named/@Singleton and are auto-discovered by Sisu.
    // No manual bindings needed - Sisu handles interface binding automatically.
  }
}
