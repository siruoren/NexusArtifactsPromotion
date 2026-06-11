package com.nexus.artifacts.promotion.resource;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.service.SyncService;

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

  @Inject
  public SyncQueueResource(final SyncService syncService) {
    this.syncService = syncService;
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

  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
        .replace("&", "&amp;");
  }
}
