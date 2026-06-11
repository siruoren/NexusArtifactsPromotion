package com.nexus.artifacts.promotion.resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.service.SyncService;
import com.nexus.artifacts.promotion.service.TaskExecutorService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * REST API for the sync queue management page.
 * Displayed under Browse menu as "Sync Queue".
 */
@Api("Sync Queue")
@Path("/v1/sync/queue")
@Named
@Singleton
public class SyncQueueResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(SyncQueueResource.class);

  private final SyncService syncService;
  private final TaskExecutorService taskExecutorService;

  @Inject
  public SyncQueueResource(final SyncService syncService, final TaskExecutorService taskExecutorService) {
    this.syncService = syncService;
    this.taskExecutorService = taskExecutorService;
  }

  /**
   * Check if the current user is authenticated (not anonymous).
   * Returns 401 if the user is not logged in.
   */
  private Response requireAuthentication() {
    Subject subject = SecurityUtils.getSubject();
    if (subject == null || !subject.isAuthenticated() || isAnonymous(subject)) {
      log.warn("Unauthenticated access attempt to sync queue API");
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity("{\"error\":\"Authentication required\",\"message\":\"Please log in to access sync queue\"}")
          .build();
    }
    return null;
  }

  /**
   * Check if the subject is the anonymous user.
   */
  private boolean isAnonymous(final Subject subject) {
    Object principal = subject.getPrincipal();
    if (principal == null) {
      return true;
    }
    String username = principal.toString();
    return "anonymous".equalsIgnoreCase(username);
  }

  /**
   * Get all sync tasks (for queue display page).
   * Shows: queue ID, source/target repo, file details, status, times, username, result.
   */
  @GET
  @Path("/list")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List all sync queue tasks")
  public Response listAllTasks()
  {
    Response authError = requireAuthentication();
    if (authError != null) {
      return authError;
    }
    try {
      List<SyncTaskInfo> tasks = syncService.getAllSyncTasks();
      return Response.ok(tasks).build();
    }
    catch (Exception e) {
      log.error("Failed to list sync tasks: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list sync tasks\"}")
          .build();
    }
  }

  /**
   * Get active (pending/running) sync tasks only.
   */
  @GET
  @Path("/active")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List active sync queue tasks")
  public Response listActiveTasks()
  {
    Response authError = requireAuthentication();
    if (authError != null) {
      return authError;
    }
    try {
      List<SyncTaskInfo> tasks = syncService.getActiveSyncTasks();
      return Response.ok(tasks).build();
    }
    catch (Exception e) {
      log.error("Failed to list active sync tasks: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list active sync tasks\"}")
          .build();
    }
  }

  /**
   * Get a specific sync task by ID.
   */
  @GET
  @Path("/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get sync task details")
  public Response getTask(@javax.ws.rs.PathParam("taskId") final String taskId)
  {
    Response authError = requireAuthentication();
    if (authError != null) {
      return authError;
    }
    try {
      SyncTaskInfo info = syncService.getTaskInfo(sanitize(taskId));
      if (info == null) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      return Response.ok(info).build();
    }
    catch (Exception e) {
      log.error("Failed to get sync task: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get sync task\"}")
          .build();
    }
  }

  /**
   * Get current queue configuration.
   */
  @GET
  @Path("/config")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get sync queue configuration")
  public Response getQueueConfig()
  {
    Response authError = requireAuthentication();
    if (authError != null) {
      return authError;
    }
    try {
      Map<String, Object> config = new HashMap<>();
      config.put("promotionPoolSize", taskExecutorService.getPromotionPoolSize());
      config.put("syncPoolSize", taskExecutorService.getSyncPoolSize());
      config.put("maxSyncQueueSize", taskExecutorService.getMaxSyncQueueSize());
      config.put("hasSyncQueueCapacity", taskExecutorService.hasSyncQueueCapacity());
      return Response.ok(config).build();
    }
    catch (Exception e) {
      log.error("Failed to get queue config: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get queue config\"}")
          .build();
    }
  }

  /**
   * Update sync queue configuration.
   * Accepts JSON body like: {"maxSyncQueueSize": 30, "syncPoolSize": 8, "promotionPoolSize": 6}
   */
  @PUT
  @Path("/config")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Update sync queue configuration")
  public Response updateQueueConfig(final Map<String, Object> config)
  {
    Response authError = requireAuthentication();
    if (authError != null) {
      return authError;
    }
    try {
      // Check promote permission for config changes
      Subject subject = SecurityUtils.getSubject();
      if (!subject.isPermitted("nexus:sync:update")) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity("{\"error\":\"Permission denied\",\"message\":\"sync update permission required\"}")
            .build();
      }

      Map<String, Object> result = new HashMap<>();

      if (config.containsKey("maxSyncQueueSize")) {
        int size = parseInt(config.get("maxSyncQueueSize"), 20);
        size = Math.max(1, Math.min(100, size));
        taskExecutorService.updateMaxSyncQueueSize(size);
        result.put("maxSyncQueueSize", taskExecutorService.getMaxSyncQueueSize());
      }

      if (config.containsKey("syncPoolSize")) {
        int size = parseInt(config.get("syncPoolSize"), 4);
        size = Math.max(1, Math.min(50, size));
        taskExecutorService.updateSyncPoolSize(size);
        result.put("syncPoolSize", taskExecutorService.getSyncPoolSize());
      }

      if (config.containsKey("promotionPoolSize")) {
        int size = parseInt(config.get("promotionPoolSize"), 4);
        size = Math.max(1, Math.min(50, size));
        taskExecutorService.updatePromotionPoolSize(size);
        result.put("promotionPoolSize", taskExecutorService.getPromotionPoolSize());
      }

      log.info("Queue configuration updated: {}", result);
      return Response.ok(result).build();
    }
    catch (Exception e) {
      log.error("Failed to update queue config: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to update queue config\"}")
          .build();
    }
  }

  private int parseInt(final Object value, final int defaultValue) {
    if (value == null) return defaultValue;
    try {
      if (value instanceof Number) {
        return ((Number) value).intValue();
      }
      return Integer.parseInt(value.toString());
    }
    catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
        .replace("&", "&amp;");
  }
}
