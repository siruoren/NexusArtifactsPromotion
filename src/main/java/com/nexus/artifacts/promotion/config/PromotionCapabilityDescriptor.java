package com.nexus.artifacts.promotion.config;

import java.util.Collections;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityDescriptorSupport;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.capability.Tag;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.NumberTextFormField;

import com.google.common.collect.Lists;

/**
 * Descriptor for the Promotion Management capability.
 * Appears in Nexus System Settings under "Promotion Management".
 */
@Named(PromotionCapabilityDescriptor.TYPE_ID)
@Singleton
public class PromotionCapabilityDescriptor extends CapabilityDescriptorSupport<Object> {

  public static final String TYPE_ID = "artifacts-promotion-management";
  public static final CapabilityType CAPABILITY_TYPE = CapabilityType.capabilityType(TYPE_ID);

  public static final String PROP_PROMOTION_POOL_SIZE = "promotionPoolSize";

  private static final NumberTextFormField POOL_SIZE_FIELD = new NumberTextFormField(
      PROP_PROMOTION_POOL_SIZE,
      "Promotion Thread Pool Size",
      "Number of threads for concurrent artifact promotion tasks (1-50, default: 4)",
      false);

  public PromotionCapabilityDescriptor() {
  }

  @Override
  public CapabilityType type() {
    return CAPABILITY_TYPE;
  }

  @Override
  public String name() {
    return "Promotion Management";
  }

  @Override
  public String about() {
    return "Configure artifact promotion settings including thread pool size";
  }

  @Override
  public java.util.List<FormField> formFields() {
    return Lists.newArrayList(POOL_SIZE_FIELD);
  }

  public Set<Tag> getTags() {
    return Collections.singleton(new Tag("promotion", "artifacts"));
  }
}
