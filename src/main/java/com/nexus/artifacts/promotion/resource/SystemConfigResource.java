package com.nexus.artifacts.promotion.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.service.TaskCacheManager;
import com.nexus.artifacts.promotion.service.TaskExecutorService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * REST API for system configuration of promotion and sync settings.
 * Accessible from Nexus System Settings.
 */
@Api("Promotion & Sync Configuration")
@Path("/v1/config")
@Named
@Singleton
public class SystemConfigResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(SystemConfigResource.class);

  private final TaskExecutorService taskExecutor;
  private final TaskCacheManager cacheManager;

  @Inject
  public SystemConfigResource(final TaskExecutorService taskExecutor,
                                final TaskCacheManager cacheManager)
  {
    this.taskExecutor = taskExecutor;
    this.cacheManager = cacheManager;
  }

  /**
   * Get current system configuration.
   */
  @GET
  @Path("/settings")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get promotion and sync system settings")
  public Response getSettings()
  {
    try {
      return Response.ok()
          .entity("{"
              + "\"promotionPoolSize\":" + taskExecutor.getPromotionPoolSize() + ","
              + "\"syncPoolSize\":" + taskExecutor.getSyncPoolSize() + ","
              + "\"maxSyncQueueSize\":" + taskExecutor.getMaxSyncQueueSize() + ","
              + "\"activeCacheCount\":" + cacheManager.getActiveCacheCount() + ","
              + "\"totalCacheSizeBytes\":" + cacheManager.getTotalCacheSize()
              + "}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to get settings: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get settings\"}")
          .build();
    }
  }

  /**
   * Update promotion thread pool size.
   */
  @PUT
  @Path("/promotion/poolSize")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Update promotion thread pool size")
  public Response updatePromotionPoolSize(@QueryParam("size") final int size)
  {
    try {
      if (size <= 0 || size > 50) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"Pool size must be between 1 and 50\"}")
            .build();
      }
      taskExecutor.updatePromotionPoolSize(size);
      return Response.ok()
          .entity("{\"promotionPoolSize\":" + size + "}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to update promotion pool size: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to update pool size\"}")
          .build();
    }
  }

  /**
   * Update sync thread pool size.
   */
  @PUT
  @Path("/sync/poolSize")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Update sync thread pool size")
  public Response updateSyncPoolSize(@QueryParam("size") final int size)
  {
    try {
      if (size <= 0 || size > 50) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"Pool size must be between 1 and 50\"}")
            .build();
      }
      taskExecutor.updateSyncPoolSize(size);
      return Response.ok()
          .entity("{\"syncPoolSize\":" + size + "}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to update sync pool size: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to update pool size\"}")
          .build();
    }
  }

  /**
   * Update max sync queue size.
   */
  @PUT
  @Path("/sync/maxQueueSize")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Update max sync queue size")
  public Response updateMaxSyncQueueSize(@QueryParam("size") final int size)
  {
    try {
      if (size <= 0 || size > 100) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"Max queue size must be between 1 and 100\"}")
            .build();
      }
      taskExecutor.updateMaxSyncQueueSize(size);
      return Response.ok()
          .entity("{\"maxSyncQueueSize\":" + size + "}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to update max sync queue size: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to update max queue size\"}")
          .build();
    }
  }
}
