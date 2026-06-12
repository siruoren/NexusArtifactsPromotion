package com.nexus.artifacts.promotion.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.exception.PermissionDeniedException;
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
 * Uses Nexus system permissions (repository-view:edit) for authorization.
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
    log.info("PromotionResource initialized successfully");
  }

  /**
   * Check if the current user has write permission on a repository.
   * Used by UI to determine whether to show the promotion button.
   */
  @GET
  @Path("/permission")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Check write permission for a repository")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Permission check result"),
      @ApiResponse(code = 403, message = "No permission")
  })
  public Response checkPermission(@QueryParam("repository") final String repository,
                                   @QueryParam("format") final String format)
  {
    try {
      boolean hasPermission = permissionChecker.hasRepositoryWritePermission(repository, format);
      return Response.ok()
          .entity("{\"hasPermission\":" + hasPermission + ",\"repository\":\""
              + jsonEscape(repository) + "\",\"format\":\"" + jsonEscape(format) + "\"}")
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
   * Only returns repositories of the same format where the user has write permission.
   */
  @GET
  @Path("/targets")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List target repositories for promotion")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Target repository list"),
      @ApiResponse(code = 403, message = "No permission")
  })
  public Response listTargets(@QueryParam("sourceRepository") final String sourceRepository,
                               @QueryParam("format") final String format)
  {
    try {
      TargetRepositoryList result = promotionService.listTargetRepositories(sourceRepository, format);
      return Response.ok(result).build();
    }
    catch (PermissionDeniedException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"Permission denied\",\"username\":\""
              + jsonEscape(e.getUsername()) + "\",\"repository\":\""
              + jsonEscape(e.getRepository()) + "\"}")
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
  public Response preview(final PromotionRequest request,
                           @Context final HttpServletRequest httpRequest)
  {
    try {
      request.validate();
      String nexusBaseUrl = extractNexusBaseUrl(httpRequest);
      FilePreviewResponse preview = promotionService.previewPromotion(request, nexusBaseUrl);
      return Response.ok(preview).build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (PermissionDeniedException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"Permission denied\",\"username\":\""
              + jsonEscape(e.getUsername()) + "\",\"repository\":\""
              + jsonEscape(e.getRepository()) + "\"}")
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
  public Response execute(final PromotionRequest request,
                           @Context final HttpHeaders httpHeaders,
                           @Context final HttpServletRequest httpRequest)
  {
    try {
      request.validate();
      // Extract user's Cookie header for HTTP-based authentication
      String cookieHeader = httpHeaders.getHeaderString(HttpHeaders.COOKIE);
      // Extract CSRF token for write operations
      String csrfToken = httpHeaders.getHeaderString("NX-ANTI-CSRF-TOKEN");
      if (csrfToken == null || csrfToken.isEmpty()) {
        csrfToken = httpHeaders.getHeaderString("nx-anti-csrf-token");
      }
      // Extract Nexus base URL from incoming request
      String nexusBaseUrl = extractNexusBaseUrl(httpRequest);
      String taskId = promotionService.promote(request, cookieHeader, csrfToken, nexusBaseUrl);
      return Response.ok()
          .entity("{\"taskId\":\"" + jsonEscape(taskId) + "\",\"status\":\"submitted\"}")
          .build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (PermissionDeniedException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"Permission denied\",\"username\":\""
              + jsonEscape(e.getUsername()) + "\",\"repository\":\""
              + jsonEscape(e.getRepository()) + "\"}")
          .build();
    }
    catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
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
      PromotionTaskResult result = promotionService.getTaskResult(taskId);
      if (result == null) {
        log.debug("Task {} not found in taskResults, checking executor", taskId);
        // Task may still be running - check TaskExecutorService
        TaskStatus status = promotionService.getTaskExecutorStatus(taskId);
        if (status != null) {
          // If task is already completed/failed but result not yet in taskResults,
          // wait briefly for the result to appear (race condition between wrapTask.finally and taskResults.put)
          if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
            for (int i = 0; i < 10; i++) {
              try { Thread.sleep(200); } catch (InterruptedException e) { break; }
              result = promotionService.getTaskResult(taskId);
              if (result != null) {
                log.debug("Task {} result appeared after waiting, status={}", taskId, result.getStatus());
                return Response.ok(result).build();
              }
            }
          }
          // Return a running status so frontend knows to keep polling
          log.debug("Task {} executor status={}, returning running", taskId, status.getValue());
          return Response.ok()
              .entity("{\"status\":\"" + status.getValue() + "\",\"taskId\":\"" + jsonEscape(taskId) + "\"}")
              .build();
        }
        log.warn("Task {} not found anywhere", taskId);
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      log.debug("Task {} found in taskResults, status={}, items={}", taskId, result.getStatus(),
          result.getItems() != null ? result.getItems().size() : 0);
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
   * Debug endpoint for collecting frontend instrumentation logs.
   * Only active during debugging - remove before production release.
   */
  @GET
  @Path("/_debug")
  @Produces(MediaType.TEXT_PLAIN)
  public Response debugLog(@QueryParam("event") final String event,
                            @QueryParam("method") final String method,
                            @QueryParam("path") final String path,
                            @QueryParam("defaultHeaderKeys") final String defaultHeaderKeys,
                            @QueryParam("csrfSource") final String csrfSource,
                            @QueryParam("hasCsrf") final String hasCsrf,
                            @QueryParam("cookieCount") final String cookieCount,
                            @QueryParam("hasNXcoreui") final String hasNXcoreui,
                            @QueryParam("hasNXview") final String hasNXview,
                            @QueryParam("hasNXcomponent") final String hasNXcomponent,
                            @QueryParam("hasFolderInfo") final String hasFolderInfo,
                            @QueryParam("hasFolderProto") final String hasFolderProto,
                            @QueryParam("xtype") final String xtype,
                            @QueryParam("hasFolderModel") final String hasFolderModel,
                            @QueryParam("overrideDone") final String overrideDone,
                            @QueryParam("fmKeys") final String fmKeys,
                            @QueryParam("folderKeys") final String folderKeys,
                            @QueryParam("repoName") final String repoName,
                            @QueryParam("path") final String pathVal)
  {
    log.info("[DEBUG-LOG] event={} method={} path={} headers={} csrf={} hasCsrf={} cookies={} " +
             "coreui={} view={} component={} folderInfo={} folderProto={} " +
             "xtype={} folderModel={} overrideDone={} fmKeys={} fKeys={} repoName={}",
        event, method, path, defaultHeaderKeys, csrfSource, hasCsrf, cookieCount,
        hasNXcoreui, hasNXview, hasNXcomponent, hasFolderInfo, hasFolderProto,
        xtype, hasFolderModel, overrideDone, fmKeys, folderKeys, repoName);
    return Response.ok().entity("ok").build();
  }

  /**
   * Extract Nexus base URL from incoming HTTP request.
   * Supports reverse proxies (X-Forwarded-Proto/Host) and HTTPS with self-signed certs.
   */
  private String extractNexusBaseUrl(final HttpServletRequest request) {
    // Check for reverse proxy headers first
    String proto = request.getHeader("X-Forwarded-Proto");
    if (proto == null || proto.isEmpty()) {
      proto = request.getHeader("X-Forwarded-Scheme");
    }
    if (proto == null || proto.isEmpty()) {
      proto = request.getScheme();
    }

    String host = request.getHeader("X-Forwarded-Host");
    if (host == null || host.isEmpty()) {
      host = request.getHeader("Host");
    }
    if (host == null || host.isEmpty()) {
      host = request.getServerName() + ":" + request.getServerPort();
    }

    // Remove port for default ports
    String baseUrl = proto + "://" + host;
    log.debug("Extracted Nexus base URL: {}", baseUrl);
    return baseUrl;
  }

  /**
   * Escape a string for safe inclusion in a manually constructed JSON response.
   * Handles quotes, backslashes, newlines, and other control characters.
   */
  private String jsonEscape(final String input) {
    if (input == null) return "";
    StringBuilder sb = new StringBuilder(input.length() + 16);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '"':  sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        default:
          if (c < ' ') {
            // Control characters: output as \\uXXXX
            sb.append("\\u");
            String hex = Integer.toHexString(c);
            for (int pad = 4 - hex.length(); pad > 0; pad--) {
              sb.append('0');
            }
            sb.append(hex);
          } else {
            sb.append(c);
          }
          break;
      }
    }
    return sb.toString();
  }
}
