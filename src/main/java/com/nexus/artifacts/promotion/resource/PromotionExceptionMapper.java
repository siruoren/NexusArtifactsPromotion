package com.nexus.artifacts.promotion.resource;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global exception mapper for the Promotion plugin REST API.
 * Provides consistent error response format across all endpoints.
 *
 * Response format:
 * <pre>
 * {
 *   "error": "Error category",
 *   "message": "Detailed error message",
 *   "status": 403
 * }
 * </pre>
 */
@Provider
@Named
@Singleton
public class PromotionExceptionMapper implements ExceptionMapper<Exception> {

  private static final Logger log = LoggerFactory.getLogger(PromotionExceptionMapper.class);

  @Override
  public Response toResponse(final Exception exception) {
    if (exception instanceof SecurityException) {
      log.warn("Permission denied: {}", exception.getMessage());
      return Response.status(Response.Status.FORBIDDEN)
          .type(MediaType.APPLICATION_JSON)
          .entity(toJson("Permission denied", exception.getMessage(), Response.Status.FORBIDDEN.getStatusCode()))
          .build();
    }

    if (exception instanceof IllegalArgumentException) {
      log.debug("Bad request: {}", exception.getMessage());
      return Response.status(Response.Status.BAD_REQUEST)
          .type(MediaType.APPLICATION_JSON)
          .entity(toJson("Bad request", exception.getMessage(), Response.Status.BAD_REQUEST.getStatusCode()))
          .build();
    }

    if (exception instanceof IllegalStateException) {
      log.warn("Service unavailable: {}", exception.getMessage());
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .type(MediaType.APPLICATION_JSON)
          .entity(toJson("Service unavailable", exception.getMessage(), Response.Status.SERVICE_UNAVAILABLE.getStatusCode()))
          .build();
    }

    if (exception instanceof java.io.IOException) {
      log.error("Remote repository error: {}", exception.getMessage());
      return Response.status(Response.Status.BAD_GATEWAY)
          .type(MediaType.APPLICATION_JSON)
          .entity(toJson("Remote repository error", exception.getMessage(), Response.Status.BAD_GATEWAY.getStatusCode()))
          .build();
    }

    if (exception instanceof RuntimeException && exception.getCause() instanceof java.io.IOException) {
      log.error("Remote repository error: {}", exception.getCause().getMessage());
      return Response.status(Response.Status.BAD_GATEWAY)
          .type(MediaType.APPLICATION_JSON)
          .entity(toJson("Remote repository error", exception.getCause().getMessage(), Response.Status.BAD_GATEWAY.getStatusCode()))
          .build();
    }

    log.error("Internal server error: {}", exception.getMessage(), exception);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .type(MediaType.APPLICATION_JSON)
        .entity(toJson("Internal server error", "An unexpected error occurred",
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()))
        .build();
  }

  private String toJson(final String error, final String message, final int status) {
    return "{\"error\":\"" + sanitize(error) + "\","
        + "\"message\":\"" + sanitize(message) + "\","
        + "\"status\":" + status + "}";
  }

  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
