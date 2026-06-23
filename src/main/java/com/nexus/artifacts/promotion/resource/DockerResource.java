package com.nexus.artifacts.promotion.resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.rest.Resource;

import com.nexus.artifacts.promotion.model.DockerImageListResponse;
import com.nexus.artifacts.promotion.model.DockerImageRequest;
import com.nexus.artifacts.promotion.model.PromotionTaskResult;
import com.nexus.artifacts.promotion.model.SyncTaskInfo;
import com.nexus.artifacts.promotion.model.TaskStatus;
import com.nexus.artifacts.promotion.security.PermissionChecker;
import com.nexus.artifacts.promotion.service.DockerService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST API for Docker image-specific operations.
 *
 * Provides efficient Docker image promotion and proxy sync by:
 * - Listing images and tags via Nexus Components API
 * - Parsing manifests to identify all referenced blobs
 * - Only transferring the blobs actually needed
 *
 * Two main workflows:
 * 1. Proxy Docker repo sync: sync all/specific image tags from remote
 * 2. Hosted Docker repo promotion: promote all/specific image tags to target repo
 */
@Api("Docker Image Operations")
@Path("/v1/docker")
@Named
@Singleton
public class DockerResource implements Resource {

  private static final Logger log = LoggerFactory.getLogger(DockerResource.class);

  private final DockerService dockerService;
  private final PermissionChecker permissionChecker;

  @Inject
  public DockerResource(final DockerService dockerService,
                         final PermissionChecker permissionChecker)
  {
    this.dockerService = dockerService;
    this.permissionChecker = permissionChecker;
    log.info("DockerResource initialized successfully");
  }

  /**
   * List all Docker images in a repository with their tags.
   * Works for both proxy and hosted Docker repositories.
   */
  @GET
  @Path("/images")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List Docker images in a repository")
  @ApiResponses({
      @ApiResponse(code = 200, message = "List of Docker images with tags"),
      @ApiResponse(code = 403, message = "No permission"),
      @ApiResponse(code = 404, message = "Repository not found")
  })
  public Response listImages(@QueryParam("repository") final String repository)
  {
    try {
      if (repository == null || repository.trim().isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"repository parameter is required\"}")
            .build();
      }

      DockerImageListResponse result = dockerService.listDockerImages(repository);
      return Response.ok(result).build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to list Docker images: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list Docker images\"}")
          .build();
    }
  }

  /**
   * List tags for a specific Docker image.
   */
  @GET
  @Path("/tags")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("List tags for a Docker image")
  @ApiResponses({
      @ApiResponse(code = 200, message = "List of tags"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 404, message = "Image not found")
  })
  public Response listTags(@QueryParam("repository") final String repository,
                           @QueryParam("image") final String image)
  {
    try {
      if (repository == null || repository.trim().isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"repository parameter is required\"}")
            .build();
      }
      if (image == null || image.trim().isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"image parameter is required\"}")
            .build();
      }

      java.util.List<String> tags = dockerService.listDockerTags(repository, image);
      return Response.ok()
          .entity("{\"repository\":\"" + jsonEscape(repository)
              + "\",\"image\":\"" + jsonEscape(image)
              + "\",\"tags\":" + toJsonArray(tags) + "}")
          .build();
    }
    catch (Exception e) {
      log.error("Failed to list Docker tags: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to list Docker tags\"}")
          .build();
    }
  }

  /**
   * Promote Docker images from source to target repository.
   * Supports promoting all tags or specific tags of an image.
   *
   * Request body:
   * {
   *   "sourceRepository": "docker-dev",
   *   "targetRepository": "docker-prod",
   *   "image": "myapp/backend",
   *   "tags": ["latest", "v1.0"],   // optional - if empty/null, all tags are promoted
   *   "format": "docker"
   * }
   */
  @POST
  @Path("/promote")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Promote Docker images between repositories")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Promotion task created"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 403, message = "No promotion permission")
  })
  public Response promote(final DockerImageRequest request,
                           @Context final HttpHeaders httpHeaders,
                           @Context final HttpServletRequest httpRequest)
  {
    try {
      request.validate();
      if (request.getTargetRepository() == null || request.getTargetRepository().trim().isEmpty()) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity("{\"error\":\"targetRepository is required for promotion\"}")
            .build();
      }

      // Extract auth info
      String cookieHeader = httpHeaders.getHeaderString(HttpHeaders.COOKIE);
      String csrfToken = httpHeaders.getHeaderString("NX-ANTI-CSRF-TOKEN");
      if (csrfToken == null || csrfToken.isEmpty()) {
        csrfToken = httpHeaders.getHeaderString("nx-anti-csrf-token");
      }
      String nexusBaseUrl = extractNexusBaseUrl(httpRequest);

      String taskId = dockerService.promoteDockerImage(request, cookieHeader, csrfToken, nexusBaseUrl);
      return Response.ok()
          .entity("{\"taskId\":\"" + jsonEscape(taskId)
              + "\",\"status\":\"submitted\""
              + ",\"allImages\":" + request.isAllImages()
              + ",\"image\":\"" + jsonEscape(request.getImage() != null ? request.getImage() : "") + "\""
              + ",\"tags\":" + (request.isAllTags() ? "\"all\"" : toJsonArray(request.getTags()))
              + ",\"message\":\"Docker promotion task created\"}")
          .build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Docker promotion failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Docker promotion failed\"}")
          .build();
    }
  }

  /**
   * Sync Docker images from a proxy repository's remote.
   * Supports syncing all tags or specific tags of an image.
   *
   * Request body:
   * {
   *   "sourceRepository": "docker-proxy",
   *   "image": "nginx",
   *   "tags": ["latest", "stable"],   // optional - if empty/null, all tags are synced
   *   "format": "docker"
   * }
   */
  @POST
  @Path("/sync")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Sync Docker images from proxy repository")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Sync task created"),
      @ApiResponse(code = 400, message = "Invalid request"),
      @ApiResponse(code = 403, message = "No sync permission"),
      @ApiResponse(code = 503, message = "Sync queue full")
  })
  public Response sync(final DockerImageRequest request)
  {
    try {
      request.validate();
      String taskId = dockerService.syncDockerImage(request);
      return Response.ok()
          .entity("{\"taskId\":\"" + jsonEscape(taskId)
              + "\",\"status\":\"submitted\""
              + ",\"allImages\":" + request.isAllImages()
              + ",\"repository\":\"" + jsonEscape(request.getSourceRepository()) + "\""
              + ",\"image\":\"" + jsonEscape(request.getImage() != null ? request.getImage() : "") + "\""
              + ",\"tags\":" + (request.isAllTags() ? "\"all\"" : toJsonArray(request.getTags()))
              + ",\"message\":\"Docker sync task created\"}")
          .build();
    }
    catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (SecurityException e) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (IllegalStateException e) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}")
          .build();
    }
    catch (Exception e) {
      log.error("Docker sync failed: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Docker sync failed\"}")
          .build();
    }
  }

  /**
   * Get Docker promotion task status.
   */
  @GET
  @Path("/promote/task/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get Docker promotion task status")
  public Response getPromotionTaskStatus(@javax.ws.rs.PathParam("taskId") final String taskId)
  {
    try {
      PromotionTaskResult result = dockerService.getPromotionTaskResult(sanitize(taskId));
      if (result == null) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      return Response.ok(result).build();
    }
    catch (Exception e) {
      log.error("Failed to get Docker promotion task status: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get task status\"}")
          .build();
    }
  }

  /**
   * Get Docker sync task status.
   */
  @GET
  @Path("/sync/task/{taskId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation("Get Docker sync task status")
  public Response getSyncTaskStatus(@javax.ws.rs.PathParam("taskId") final String taskId)
  {
    try {
      SyncTaskInfo info = dockerService.getSyncTaskInfo(sanitize(taskId));
      if (info == null) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity("{\"error\":\"Task not found\"}")
            .build();
      }
      return Response.ok(info).build();
    }
    catch (Exception e) {
      log.error("Failed to get Docker sync task status: {}", e.getMessage());
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("{\"error\":\"Failed to get task status\"}")
          .build();
    }
  }

  // ==================== Helpers ====================

  /**
   * Extract Nexus base URL from incoming HTTP request.
   * Supports reverse proxies (X-Forwarded-Proto only) and HTTPS with self-signed certs.
   *
   * <p>Security: X-Forwarded-Host is NOT used for target host determination to prevent
   * SSRF attacks. Only X-Forwarded-Proto is allowed (affects scheme only, not target host).
   * The host is always derived from the request's own server info and validated as local.
   */
  private String extractNexusBaseUrl(final HttpServletRequest request) {
    // Determine scheme - X-Forwarded-Proto is safe for scheme only (http vs https)
    String scheme = request.getScheme();
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    if (forwardedProto == null || forwardedProto.isEmpty()) {
      forwardedProto = request.getHeader("X-Forwarded-Scheme");
    }
    if (forwardedProto != null && !forwardedProto.isEmpty()) {
      String proto = forwardedProto.toLowerCase();
      if ("https".equals(proto) || "http".equals(proto)) {
        scheme = proto;
      }
    }

    // Security: use request's own server info for host, NOT X-Forwarded-Host (SSRF risk)
    String host = request.getServerName();
    int port = request.getServerPort();

    // Validate host is local to prevent SSRF
    if (!isLocalHost(host)) {
      log.warn("Server host '{}' is not local, using localhost as fallback for internal API calls", host);
      host = "localhost";
    }

    // Build URL
    String baseUrl;
    if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
      baseUrl = scheme + "://" + host;
    }
    else {
      baseUrl = scheme + "://" + host + ":" + port;
    }

    log.debug("Extracted Nexus base URL: {}", baseUrl);
    return baseUrl;
  }

  /**
   * Check if a hostname refers to the local machine.
   * Used to validate that internal API calls only target local addresses.
   */
  private boolean isLocalHost(final String host) {
    if (host == null) return false;
    return "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "[::1]".equals(host)
        || "0:0:0:0:0:0:0:1".equals(host);
  }

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
        default:
          if (c < ' ') {
            sb.append("\\u");
            String hex = Integer.toHexString(c);
            for (int pad = 4 - hex.length(); pad > 0; pad--) sb.append('0');
            sb.append(hex);
          }
          else { sb.append(c); }
          break;
      }
    }
    return sb.toString();
  }

  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#x27;").replace("&", "&amp;");
  }

  private String toJsonArray(final java.util.List<String> items) {
    if (items == null || items.isEmpty()) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(jsonEscape(items.get(i))).append("\"");
    }
    sb.append("]");
    return sb.toString();
  }
}
