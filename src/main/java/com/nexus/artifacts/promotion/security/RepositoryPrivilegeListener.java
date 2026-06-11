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

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for repository creation/deletion events and automatically
 * adds/removes corresponding promotion and sync privilege entries.
 *
 * Uses PrivilegeInitializer to persist privileges through
 * SecurityConfigurationManager so they appear in role management UI.
 */
@Named
@Singleton
public class RepositoryPrivilegeListener
{
  private static final Logger log = LoggerFactory.getLogger(RepositoryPrivilegeListener.class);

  private final PrivilegeInitializer privilegeInitializer;
  private final EventBus eventBus;

  @Inject
  public RepositoryPrivilegeListener(final PrivilegeInitializer privilegeInitializer,
                                      final EventBus eventBus) {
    this.privilegeInitializer = privilegeInitializer;
    this.eventBus = eventBus;
  }

  @PostConstruct
  public void init() {
    eventBus.register(this);
    // Defer privilege initialization to avoid blocking startup
    // if SecurityConfigurationManager is not yet available
    try {
      privilegeInitializer.ensureInitialized();
    }
    catch (Exception e) {
      log.warn("Deferred privilege initialization: {}", e.getMessage());
    }
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

    privilegeInitializer.createPromotionPrivilege(repoName, format);
    privilegeInitializer.createSyncPrivilege(repoName, format);
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

    privilegeInitializer.deletePromotionPrivilege(repoName, format);
    privilegeInitializer.deleteSyncPrivilege(repoName, format);
  }
}
