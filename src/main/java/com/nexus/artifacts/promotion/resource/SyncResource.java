package com.nexus.artifacts.promotion.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.SyncRequest;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.security.PermissionChecker;
import com.nexus.artifacts.promotion.service.SyncService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST API for remote repository sync operations.
 * All endpoints enforce permission checks.
 */
@Api("Remote Repository Sync")
@Path("/v1/sync")
@Named
@Singleton
public class SyncResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(SyncResource.class);

  private final SyncService syncService;
  private final PermissionChecker permissionChecker;
  private final RepositoryManager repositoryManager;

  @Inject
  public SyncResource(final SyncService syncService,
                       final PermissionChecker permissionChecker,
                       final RepositoryManager repositoryManager)
  {
    this.syncService = syncService;
    this.permissionChecker = permissionChecker;
    this.repositoryManager = repositoryManager;
  }

  /**
   * Check if the current user has sync permission for a repository.
   * Used by UI to determine whether to show the sync button.
   */
  @GET
  @Path("/permission")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Check sync permission for a repository")
  public Response checkPermission(@QueryParam("repository") final String repository,
                                   @QueryParam("format") final String format)
  {
    try {
      boolean hasPermission = permissionChecker.hasSyncPermission(repository, format);
      boolean isProxy = permissionChecker.isProxyRepository(repository);
      boolean hasDeletePermission = isProxy && permissionChecker.hasRepositoryDeletePermission(repository, format);
      return Response.ok()
          .entity("{\"hasPermission\":" + hasPermission
              + ",\"isProxy\":" + isProxy
              + ",\"hasDeletePermission\":" + hasDeletePermission
              + ",\"repository\":\"" + sanitize(repository) + "\""
              + ",\"format\":\"" + sanitize(format) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Sync permission check failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Permission check failed\"}")
          .build();
    }
  }

  /**
   * Execute sync of a remote repository path.
   * Returns the sync queue task ID.
   */
  @POST
  @Path("/execute")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Execute remote repository sync")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Sync task created"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 403, message = "No sync permission"),
      @ApiResponse(code = 503, message = "Sync queue full")
  })
  public Response execute(final SyncRequest request)
  {
    try {
      request.validate();
      String taskId = syncService.sync(request);
      return Response.ok()
          .entity("{\"taskId\":\"" + sanitize(taskId) + "\",\"status\":\"submitted\","
              + "\"repository\":\"" + sanitize(request.getRepositoryName()) + "\","
              + "\"path\":\"" + sanitize(request.getPath()) + "\","
              + "\"isDirectory\":" + request.isDirectory() + ","
              + "\"message\":\"Sync queue task created\"}")
          .build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"" + sanitize(e.getMessage()) + "\"}")
          .build();
    }
    catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"" + sanitize(e.getMessage()) + "\"}")
          .build();
    }
    catch (IllegalStateException e) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("{\"error\":\"" + sanitize(e.getMessage()) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Sync execution failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Sync execution failed\"}")
          .build();
    }
  }

  /**
   * Get sync task status.
   */
  @GET
  @Path("/task/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get sync task status")
  public Response getTaskStatus(@javax.ws.rs.PathParam("taskId") final String taskId)
  {
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
      log.error("Failed to get sync task status: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get task status\"}")
          .build();
    }
  }

  /**
   * List all proxy repositories.
   * Returns repository name, format, and remote URL for each proxy repository.
   */
  @GET
  @Path("/proxy-repositories")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List all proxy repositories")
  public Response listProxyRepositories()
  {
    try {
      StringBuilder json = new StringBuilder("[");
      boolean first = true;
      for (Repository repo : repositoryManager.browse()) {
        if ("proxy".equals(repo.getType().getValue())) {
          if (!first) {
            json.append(",");
          }
          first = false;

          String format = repo.getFormat() != null ? repo.getFormat().getValue() : "unknown";
          String remoteUrl = "";
          try {
            org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
            if (config != null && config.getAttributes() != null
                && config.getAttributes().containsKey("proxy")) {
              @SuppressWarnings("unchecked")
              java.util.Map<String, Object> proxyAttrs = config.getAttributes().get("proxy");
              Object urlObj = proxyAttrs.get("remoteUrl");
              if (urlObj != null) {
                remoteUrl = urlObj.toString();
              }
            }
          }
          catch (Exception ignored) {
            // ignore
          }

          boolean hasDeletePerm = permissionChecker.hasRepositoryDeletePermission(repo.getName(), format);

          json.append("{")
              .append("\"name\":\"").append(sanitize(repo.getName())).append("\",")
              .append("\"format\":\"").append(sanitize(format)).append("\",")
              .append("\"remoteUrl\":\"").append(sanitize(remoteUrl)).append("\",")
              .append("\"hasDeletePermission\":").append(hasDeletePerm)
              .append("}");
        }
      }
      json.append("]");
      return Response.ok().entity(json.toString()).build();
    }
    catch (Exception e) {
      log.error("Failed to list proxy repositories: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list proxy repositories\"}")
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
