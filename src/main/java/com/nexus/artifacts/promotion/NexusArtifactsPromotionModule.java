package com.nexus.artifacts.promotion;

import org.eclipse.sisu.EagerSingleton;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.rest.Component;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;

import com.google.inject.AbstractModule;
import com.nexus.artifacts.promotion.config.PromotionCapabilityDescriptor;
import com.nexus.artifacts.promotion.config.SyncCapabilityDescriptor;
import com.nexus.artifacts.promotion.security.PromotionPrivilegeDescriptor;
import com.nexus.artifacts.promotion.security.SyncPrivilegeDescriptor;

/**
 * Guice module for Nexus Artifacts Promotion Plugin.
 * Binds privilege descriptors and capability descriptors.
 * Services and resources are auto-discovered via Sisu @Named/@Singleton annotations.
 */
public class NexusArtifactsPromotionModule extends AbstractModule {

  @Override
  protected void configure() {
    // Bind privilege descriptors
    bind(PrivilegeDescriptor.class)
        .annotatedWith(com.google.inject.name.Names.named(PromotionPrivilegeDescriptor.ID))
        .to(PromotionPrivilegeDescriptor.class);
    bind(PrivilegeDescriptor.class)
        .annotatedWith(com.google.inject.name.Names.named(SyncPrivilegeDescriptor.ID))
        .to(SyncPrivilegeDescriptor.class);

    // Bind capability descriptors
    bind(CapabilityDescriptor.class)
        .annotatedWith(com.google.inject.name.Names.named(PromotionCapabilityDescriptor.TYPE_ID))
        .to(PromotionCapabilityDescriptor.class);
    bind(CapabilityDescriptor.class)
        .annotatedWith(com.google.inject.name.Names.named(SyncCapabilityDescriptor.TYPE_ID))
        .to(SyncCapabilityDescriptor.class);
  }
}
