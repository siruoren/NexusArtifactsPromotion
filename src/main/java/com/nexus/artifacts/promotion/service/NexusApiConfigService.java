package com.nexus.artifacts.promotion.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds configurable Nexus API settings for search/component listing.
 *
 * These settings control:
 * - API page size: how many items are returned per API call (default: 50, max: 1000)
 * - Sort field: the field used to sort search results (default: "version")
 * - Sort direction: asc or desc (default: "desc" for newest first)
 *
 * Values can be changed at runtime via the Promotion Capability configuration.
 */
@Named
@Singleton
public class NexusApiConfigService {

  private static final Logger log = LoggerFactory.getLogger(NexusApiConfigService.class);

  /** Default page size for Nexus REST API calls */
  public static final int DEFAULT_API_PAGE_SIZE = 50;

  /** Maximum allowed page size */
  public static final int MAX_API_PAGE_SIZE = 1000;

  /** Minimum allowed page size */
  public static final int MIN_API_PAGE_SIZE = 1;

  /** Default sort field for search results */
  public static final String DEFAULT_SORT_FIELD = "version";

  /** Default sort direction */
  public static final String DEFAULT_SORT_DIRECTION = "desc";

  private final AtomicInteger apiPageSize = new AtomicInteger(DEFAULT_API_PAGE_SIZE);
  private final AtomicReference<String> sortField = new AtomicReference<>(DEFAULT_SORT_FIELD);
  private final AtomicReference<String> sortDirection = new AtomicReference<>(DEFAULT_SORT_DIRECTION);

  @Inject
  public NexusApiConfigService() {
    log.info("NexusApiConfigService initialized with defaults: pageSize={}, sortField={}, sortDirection={}",
        DEFAULT_API_PAGE_SIZE, DEFAULT_SORT_FIELD, DEFAULT_SORT_DIRECTION);
  }

  public int getApiPageSize() {
    return apiPageSize.get();
  }

  public void updateApiPageSize(final int size) {
    if (size < MIN_API_PAGE_SIZE || size > MAX_API_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "API page size must be between " + MIN_API_PAGE_SIZE + " and " + MAX_API_PAGE_SIZE);
    }
    int old = apiPageSize.getAndSet(size);
    log.info("API page size updated from {} to {}", old, size);
  }

  public String getSortField() {
    return sortField.get();
  }

  public void updateSortField(final String field) {
    String old = sortField.getAndSet(field);
    log.info("Sort field updated from {} to {}", old, field);
  }

  public String getSortDirection() {
    return sortDirection.get();
  }

  public void updateSortDirection(final String direction) {
    if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
      throw new IllegalArgumentException("Sort direction must be 'asc' or 'desc'");
    }
    String old = sortDirection.getAndSet(direction.toLowerCase());
    log.info("Sort direction updated from {} to {}", old, direction);
  }
}
