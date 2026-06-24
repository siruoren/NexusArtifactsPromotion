package com.nexus.artifacts.promotion.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;

/**
 * Shared utility methods for promotion and sync services.
 */
public class ServiceUtils {

  private static final Logger log = LoggerFactory.getLogger(ServiceUtils.class);

  private ServiceUtils() {}

  /**
   * Sanitize error message by masking sensitive information (passwords, tokens, secrets, credentials).
   */
  public static String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }

  /**
   * Extract HTTP authentication credentials from repository configuration.
   *
   * @return String array with [username, password], or null if no auth configured
   */
  public static String[] extractAuthFromRepo(final Repository repo) {
    try {
      org.sonatype.nexus.repository.config.Configuration config = repo.getConfiguration();
      if (config == null) return null;
      Map<String, Map<String, Object>> attributes = config.getAttributes();
      if (attributes == null || !attributes.containsKey("httpclient")) return null;
      @SuppressWarnings("unchecked")
      Map<String, Object> httpClientAttrs = attributes.get("httpclient");
      @SuppressWarnings("unchecked")
      Map<String, Object> authAttrs = (Map<String, Object>) httpClientAttrs.get("authentication");
      if (authAttrs == null) return null;
      String username = (String) authAttrs.get("username");
      String password = (String) authAttrs.get("password");
      if (username != null && password != null) return new String[]{username, password};
    }
    catch (Exception e) {
      log.debug("Failed to extract auth from repo {}: {}", repo.getName(), e.getMessage());
    }
    return null;
  }

  /**
   * Base64-encode a string for HTTP Basic authentication.
   */
  public static String encodeAuth(final String userPass) {
    return java.util.Base64.getEncoder().encodeToString(userPass.getBytes());
  }
}
