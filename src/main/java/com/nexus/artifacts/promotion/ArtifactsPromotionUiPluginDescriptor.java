package com.nexus.artifacts.promotion;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.rapture.UiPluginDescriptorSupport;

/**
 * UI Plugin Descriptor for Nexus Artifacts Promotion Plugin.
 * Registers the frontend JS module with Nexus Rapture framework.
 *
 * The JS file is loaded from: static/rapture/nexus-artifacts-promotion-plugin.js
 * The namespace is: NX.artifactsPromotion
 * The config class is: NX.artifactsPromotion.app.PluginConfig
 */
@Named
@Singleton
public class ArtifactsPromotionUiPluginDescriptor extends UiPluginDescriptorSupport {

  public ArtifactsPromotionUiPluginDescriptor() {
    super("nexus-artifacts-promotion-plugin");
    setNamespace("NX.artifactsPromotion");
    setConfigClassName("NX.artifactsPromotion.app.PluginConfig");
    setHasScript(true);
    setHasStyle(false);
  }
}
