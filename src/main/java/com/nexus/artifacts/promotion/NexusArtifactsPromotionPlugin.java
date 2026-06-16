package com.nexus.artifacts.promotion;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nexus Artifacts Promotion Plugin identity.
 * Compatible with both Nexus PRO and OSS editions.
 *
 * Note: Nexus 3.45.0 does not expose a public PluginIdentity base class
 * in the standard API. This class serves as the plugin descriptor
 * registered via OSGi Declarative Services.
 */
@Named
@Singleton
public class NexusArtifactsPromotionPlugin {

  private static final Logger log = LoggerFactory.getLogger(NexusArtifactsPromotionPlugin.class);

  public static final String GROUP_ID = "com.nexus.artifacts";

  public static final String ARTIFACT_ID = "nexus-artifacts-promotion-plugin";

  public static final String VERSION = readVersion();

  public NexusArtifactsPromotionPlugin() {
    log.info("Nexus Artifacts Promotion Plugin v{} initialized", VERSION);
  }

  private static String readVersion() {
    try {
      return NexusArtifactsPromotionPlugin.class.getPackage().getImplementationVersion();
    }
    catch (Exception e) {
      return "1.0.0-SNAPSHOT";
    }
  }

  public String getId() {
    return ARTIFACT_ID;
  }

  public String getVersion() {
    return VERSION;
  }

  public String getName() {
    return "Nexus Artifacts Promotion";
  }
}
