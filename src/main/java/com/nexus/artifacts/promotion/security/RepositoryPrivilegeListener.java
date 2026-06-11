package com.nexus.artifacts.promotion.security;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventBus;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryCreatedEvent;
import org.sonatype.nexus.repository.manager.RepositoryDeletedEvent;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for repository creation/deletion events and automatically
 * adds/removes corresponding promotion and sync privilege entries.
 *
 * This ensures that when a new repository is created, administrators
 * can immediately assign promotion/sync permissions for it.
 */
@Named
@Singleton
public class RepositoryPrivilegeListener
{
  private static final Logger log = LoggerFactory.getLogger(RepositoryPrivilegeListener.class);

  private final SecurityConfigurationManager securityConfigManager;
  private final EventBus eventBus;

  @Inject
  public RepositoryPrivilegeListener(final SecurityConfigurationManager securityConfigManager,
                                      final EventBus eventBus) {
    this.securityConfigManager = securityConfigManager;
    this.eventBus = eventBus;
  }

  @PostConstruct
  public void init() {
    eventBus.register(this);
    log.info("RepositoryPrivilegeListener registered with EventBus");
  }

  @PreDestroy
  public void destroy() {
    try {
      eventBus.unregister(this);
    }
    catch (Exception e) {
      log.debug("Error unregistering from EventBus: {}", e.getMessage());
    }
  }

  /**
   * Handle repository creation - add promotion and sync privilege entries.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void onRepositoryCreated(final RepositoryCreatedEvent event) {
    Repository repo = event.getRepository();
    String repoName = repo.getName();
    String format = repo.getFormat().getValue();

    log.info("Repository created: {} (format: {}), adding promotion/sync privileges", repoName, format);

    try {
      CPrivilege promoPrivilege = PromotionSecurityContributor.createPromotionPrivilege(repoName, format);
      securityConfigManager.createPrivilege(promoPrivilege);
      log.info("Created promotion privilege for repository: {}", repoName);
    }
    catch (Exception e) {
      log.debug("Could not create promotion privilege for {}: {}", repoName, e.getMessage());
    }

    try {
      CPrivilege syncPrivilege = SyncSecurityContributor.createSyncPrivilege(repoName, format);
      securityConfigManager.createPrivilege(syncPrivilege);
      log.info("Created sync privilege for repository: {}", repoName);
    }
    catch (Exception e) {
      log.debug("Could not create sync privilege for {}: {}", repoName, e.getMessage());
    }
  }

  /**
   * Handle repository deletion - remove promotion and sync privilege entries.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void onRepositoryDeleted(final RepositoryDeletedEvent event) {
    Repository repo = event.getRepository();
    String repoName = repo.getName();
    String format = repo.getFormat().getValue();

    log.info("Repository deleted: {} (format: {}), removing promotion/sync privileges", repoName, format);

    try {
      String promoPrivilegeId = PromotionSecurityContributor.getPrivilegeId(repoName, format);
      securityConfigManager.deletePrivilege(promoPrivilegeId);
      log.info("Deleted promotion privilege for repository: {}", repoName);
    }
    catch (Exception e) {
      log.debug("Could not delete promotion privilege for {}: {}", repoName, e.getMessage());
    }

    try {
      String syncPrivilegeId = SyncSecurityContributor.getPrivilegeId(repoName, format);
      securityConfigManager.deletePrivilege(syncPrivilegeId);
      log.info("Deleted sync privilege for repository: {}", repoName);
    }
    catch (Exception e) {
      log.debug("Could not delete sync privilege for {}: {}", repoName, e.getMessage());
    }
  }
}
