package com.nexus.artifacts.promotion.exception;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global exception mapper for the promotion plugin REST API.
 * Ensures consistent error responses and prevents information leakage.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);

  @Override
  public Response toResponse(final Exception exception) {
    if (exception instanceof PermissionDeniedException) {
      PermissionDeniedException pde = (PermissionDeniedException) exception;
      log.warn("Permission denied: {}", exception.getMessage());
      return Response.status(Response.Status.FORBIDDEN)
          .type(MediaType.APPLICATION_JSON)
          .entity("{\"error\":\"Permission denied\",\"username\":\"" +
              sanitize(pde.getUsername()) + "\",\"repository\":\"" +
              sanitize(pde.getRepository()) + "\",\"message\":\"" +
              sanitize(exception.getMessage()) + "\"}")
          .build();
    }

    if (exception instanceof QueueFullException) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .type(MediaType.APPLICATION_JSON)
          .entity("{\"error\":\"Queue full\",\"message\":\"" +
              sanitize(exception.getMessage()) + "\"}")
          .build();
    }

    if (exception instanceof TaskTimeoutException) {
      return Response.status(Response.Status.REQUEST_TIMEOUT)
          .type(MediaType.APPLICATION_JSON)
          .entity("{\"error\":\"Task timeout\",\"message\":\"" +
              sanitize(exception.getMessage()) + "\"}")
          .build();
    }

    if (exception instanceof IllegalArgumentException) {
      return Response.status(Response.Status.BAD_REQUEST)
          .type(MediaType.APPLICATION_JSON)
          .entity("{\"error\":\"Bad request\",\"message\":\"" +
              sanitize(exception.getMessage()) + "\"}")
          .build();
    }

    if (exception instanceof SecurityException) {
      log.warn("Security violation: {}", exception.getMessage());
      return Response.status(Response.Status.FORBIDDEN)
          .type(MediaType.APPLICATION_JSON)
          .entity("{\"error\":\"Access denied\"}")
          .build();
    }

    log.error("Unhandled exception in REST API: {}", exception.getMessage(), exception);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .type(MediaType.APPLICATION_JSON)
        .entity("{\"error\":\"Internal server error\"}")
        .build();
  }

  private String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
        .replace("&", "&amp;")
        .replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }
}
