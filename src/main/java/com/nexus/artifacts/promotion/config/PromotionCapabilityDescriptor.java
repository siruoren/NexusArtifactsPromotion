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
import org.sonatype.nexus.formfields.StringTextFormField;

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
  public static final String PROP_DOCKER_RELEASE_REPOS = "dockerReleaseRepos";

  private static final NumberTextFormField POOL_SIZE_FIELD = new NumberTextFormField(
      PROP_PROMOTION_POOL_SIZE,
      "Promotion Thread Pool Size",
      "Number of threads for concurrent artifact promotion tasks (1-50, default: 4)",
      false);

  private static final StringTextFormField DOCKER_RELEASE_REPOS_FIELD = new StringTextFormField(
      PROP_DOCKER_RELEASE_REPOS,
      "Docker Release Repositories",
      "Comma-separated list of Docker host repository names that are release repositories. "
          + "When promoting images to a release repository, only release tags are allowed "
          + "(tags containing SNAPSHOT/dev/alpha/beta/RC are filtered out). Example: docker-release,prod-docker",
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
    return "Configure artifact promotion settings including thread pool size and Docker release repositories";
  }

  @Override
  public java.util.List<FormField> formFields() {
    return Lists.newArrayList(POOL_SIZE_FIELD, DOCKER_RELEASE_REPOS_FIELD);
  }

  public Set<Tag> getTags() {
    return Collections.singleton(new Tag("promotion", "artifacts"));
  }
}
