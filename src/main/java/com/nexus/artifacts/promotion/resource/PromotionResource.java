package com.nexus.artifacts.promotion.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.FilePreviewResponse;
import com.nexus.artifacts.promotion.model.PromotionRequest;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.TargetRepositoryList;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;
import com.nexus.artifacts.promotion.service.PromotionService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST API for artifact promotion operations.
 * All endpoints enforce permission checks.
 */
@Api("Artifact Promotion")
@Path("/v1/promotion")
@Named
@Singleton
public class PromotionResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(PromotionResource.class);

  private final PromotionService promotionService;
  private final PermissionChecker permissionChecker;

  @Inject
  public PromotionResource(final PromotionService promotionService,
                            final PermissionChecker permissionChecker)
  {
    this.promotionService = promotionService;
    this.permissionChecker = permissionChecker;
  }

  /**
   * Check if the current user has promotion permission for a repository.
   * Used by UI to determine whether to show the promotion button.
   */
  @GET
  @Path("/permission")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Check promotion permission for a repository")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Permission check result"),
      @ApiResponse(code = 403, message = "No permission")
  })
  public Response checkPermission(@QueryParam("repository") final String repository,
                                   @QueryParam("format") final String format)
  {
    try {
      boolean hasPermission = permissionChecker.hasPromotionPermission(repository, format);
      return Response.ok()
          .entity("{\"hasPermission\":" + hasPermission + ",\"repository\":\""
              + sanitize(repository) + "\",\"format\":\"" + sanitize(format) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Permission check failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Permission check failed\"}")
          .build();
    }
  }

  /**
   * List target repositories available for promotion.
   * Only returns repositories of the same format where the user has promotion permission.
   */
  @GET
  @Path("/targets")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List target repositories for promotion")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Target repository list"),
      @ApiResponse(code = 403, message = "No promotion permission")
  })
  public Response listTargets(@QueryParam("sourceRepository") final String sourceRepository,
                               @QueryParam("format") final String format)
  {
    try {
      TargetRepositoryList result = promotionService.listTargetRepositories(sourceRepository, format);
      return Response.ok(result).build();
    }
    catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"" + sanitize(e.getMessage()) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to list target repositories: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list target repositories\"}")
          .build();
    }
  }

  /**
   * Preview files that will be involved in a promotion.
   */
  @POST
  @Path("/preview")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Preview promotion files")
  @ApiResponses({
      @ApiResponse(code = 200, message = "File preview"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 403, message = "No promotion permission")
  })
  public Response preview(@Valid final PromotionRequest request)
  {
    try {
      request.validate();
      FilePreviewResponse preview = promotionService.previewPromotion(request);
      return Response.ok(preview).build();
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
    catch (Exception e) {
      log.error("Preview failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Preview failed\"}")
          .build();
    }
  }

  /**
   * Execute artifact promotion.
   * Submits to thread pool and returns task ID.
   * Frontend polls for task status.
   */
  @POST
  @Path("/execute")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Execute artifact promotion")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Promotion task created"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 403, message = "No promotion permission")
  })
  public Response execute(@Valid final PromotionRequest request)
  {
    try {
      request.validate();
      String taskId = promotionService.promote(request);
      return Response.ok()
          .entity("{\"taskId\":\"" + sanitize(taskId) + "\",\"status\":\"submitted\"}")
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
    catch (Exception e) {
      log.error("Promotion execution failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Promotion execution failed\"}")
          .build();
    }
  }

  /**
   * Get promotion task status and result.
   * Used for frontend polling.
   */
  @GET
  @Path("/task/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get promotion task status")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Task status"),
      @ApiResponse(code = 404, message = "Task not found")
  })
  public Response getTaskStatus(@PathParam("taskId") final String taskId)
  {
    try {
      PromotionTaskResult result = promotionService.getTaskResult(sanitize(taskId));
      if (result == null) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      return Response.ok(result).build();
    }
    catch (Exception e) {
      log.error("Failed to get task status: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get task status\"}")
          .build();
    }
  }

  /**
   * Sanitize output to prevent XSS.
   */
  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
        .replace("&", "&amp;");
  }
}
