package com.nexus.artifacts.promotion.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.repository.Repository;

/**
 * Shared utility methods for promotion and sync services.
 * Consolidates common JSON parsing, HTTP helpers, and string utilities
 * that were previously duplicated across multiple service classes.
 */
public class ServiceUtils {

  private static final Logger log = LoggerFactory.getLogger(ServiceUtils.class);

  private ServiceUtils() {}

  // ==================== Error Message Sanitization ====================

  /**
   * Sanitize error message by masking sensitive information (passwords, tokens, secrets, credentials).
   */
  public static String sanitizeErrorMessage(final String message) {
    if (message == null) return "Unknown error";
    return message.replaceAll("(?i)(password|token|secret|credential)\\s*[:=]\\s*\\S+", "$1:***");
  }

  // ==================== Repository Auth ====================

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

  // ==================== JSON Parsing Utilities ====================

  /**
   * Find matching closing brace for a JSON object.
   * Handles nested braces and string escaping.
   */
  public static int findMatchingBrace(final String s, final int start) {
    int depth = 0;
    boolean inString = false;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString) {
        if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  /**
   * Find matching closing bracket for a JSON array.
   * Handles nested brackets and string escaping.
   */
  public static int findMatchingBracket(final String s, final int openPos) {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString) {
        if (c == '[') depth++;
        else if (c == ']') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  /**
   * Extract a string value by key from a JSON object string.
   * Simple parser that handles escaped characters.
   */
  public static String extractJsonValue(final String json, final String key) {
    String searchKey = "\"" + key + "\"";
    int keyIdx = json.indexOf(searchKey);
    if (keyIdx < 0) return null;
    int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
    if (colonIdx < 0) return null;

    // Skip whitespace
    int valStart = colonIdx + 1;
    while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) {
      valStart++;
    }
    if (valStart >= json.length()) return null;

    // Check for null
    if (json.startsWith("null", valStart)) return null;

    // Parse string value
    if (json.charAt(valStart) != '"') return null;
    valStart++;
    StringBuilder sb = new StringBuilder();
    int i = valStart;
    while (i < json.length()) {
      char c = json.charAt(i);
      if (c == '\\') {
        if (i + 1 < json.length()) {
          sb.append(json.charAt(i + 1));
          i += 2;
          continue;
        }
      }
      else if (c == '"') {
        break;
      }
      sb.append(c);
      i++;
    }
    return sb.toString();
  }

  /**
   * Extract a JSON array (without brackets) by key from a JSON object string.
   * Returns the content between [ and ] (exclusive of brackets).
   */
  public static String extractJsonArray(final String json, final String key) {
    String pattern = "\"" + key + "\"";
    int keyIdx = json.indexOf(pattern);
    if (keyIdx < 0) return null;
    int colonIdx = json.indexOf(':', keyIdx + pattern.length());
    if (colonIdx < 0) return null;
    int arrayStart = json.indexOf('[', colonIdx + 1);
    if (arrayStart < 0) return null;
    int arrayEnd = findMatchingBracket(json, arrayStart);
    if (arrayEnd < 0) return null;
    return json.substring(arrayStart + 1, arrayEnd);
  }

  /**
   * Parse continuationToken from Nexus API JSON response.
   */
  public static String parseContinuationToken(final String json) {
    return extractJsonValue(json, "continuationToken");
  }

  // ==================== HTTP Request Utilities ====================

  /**
   * Extract Nexus base URL from incoming HTTP request.
   * Supports reverse proxies (X-Forwarded-Proto only) and HTTPS with self-signed certs.
   *
   * <p>Security: X-Forwarded-Host is NOT used for target host determination to prevent
   * SSRF attacks. Only X-Forwarded-Proto is allowed (affects scheme only, not target host).
   * The host is always derived from the request's own server info and validated as local.
   */
  public static String extractNexusBaseUrl(final HttpServletRequest request) {
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

    String host = request.getServerName();
    int port = request.getServerPort();

    if (!isLocalHost(host)) {
      log.warn("Server host '{}' is not local, using localhost as fallback for internal API calls", host);
      host = "localhost";
    }

    if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
      return scheme + "://" + host;
    }
    return scheme + "://" + host + ":" + port;
  }

  /**
   * Check if a hostname refers to the local machine.
   */
  public static boolean isLocalHost(final String host) {
    if (host == null) return false;
    return "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "[::1]".equals(host)
        || "0:0:0:0:0:0:0:1".equals(host);
  }

  // ==================== String Escaping Utilities ====================

  /**
   * Escape a string for safe inclusion in a manually constructed JSON response.
   * Handles quotes, backslashes, newlines, and other control characters.
   */
  public static String jsonEscape(final String input) {
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
            sb.append("\\u");
            String hex = Integer.toHexString(c);
            for (int pad = 4 - hex.length(); pad > 0; pad--) {
              sb.append('0');
            }
            sb.append(hex);
          }
          else {
            sb.append(c);
          }
          break;
      }
    }
    return sb.toString();
  }

  /**
   * Sanitize a string for safe inclusion in HTML/JSON output.
   * Escapes HTML special characters to prevent XSS.
   */
  public static String sanitize(final String input) {
    if (input == null) return "";
    return input.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }

  /**
   * Convert a list of strings to a JSON array string.
   */
  public static String toJsonArray(final java.util.List<String> items) {
    if (items == null || items.isEmpty()) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(jsonEscape(items.get(i))).append("\"");
    }
    sb.append("]");
    return sb.toString();
  }

  // ==================== Local Nexus URL Detection ====================

  /** Nexus local base URLs for internal API calls */
  private static final String LOCAL_NEXUS_BASE_HTTPS = "https://localhost:8081";
  private static final String LOCAL_NEXUS_BASE_HTTP = "http://localhost:8081";

  /** Cached working local base URL */
  private static volatile String cachedLocalNexusBase = null;

  /**
   * Get the working local Nexus base URL.
   * Tries HTTPS first (for Nexus with SSL), then falls back to HTTP.
   * Result is cached after first successful connection.
   */
  public static String getLocalNexusBase() {
    if (cachedLocalNexusBase != null) {
      return cachedLocalNexusBase;
    }

    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTPS)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTPS;
      log.debug("Local Nexus base URL resolved to HTTPS: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    if (testLocalConnection(LOCAL_NEXUS_BASE_HTTP)) {
      cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
      log.debug("Local Nexus base URL resolved to HTTP: {}", cachedLocalNexusBase);
      return cachedLocalNexusBase;
    }

    log.warn("Could not determine local Nexus base URL, defaulting to HTTP");
    cachedLocalNexusBase = LOCAL_NEXUS_BASE_HTTP;
    return cachedLocalNexusBase;
  }

  /**
   * Test if a local Nexus URL is reachable.
   */
  public static boolean testLocalConnection(final String baseUrl) {
    try {
      URL url = new URL(baseUrl + "/service/rest/v1/status");
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      SslHelper.applyTrustAllSsl(conn);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      int code = conn.getResponseCode();
      conn.disconnect();
      return code < 500;
    }
    catch (Exception e) {
      log.debug("Local connection test failed for {}: {}", baseUrl, e.getMessage());
      return false;
    }
  }

  // ==================== Stream Utilities ====================

  /**
   * Read entire stream into a string using UTF-8 encoding.
   */
  public static String readStream(final InputStream input) throws IOException {
    if (input == null) return "";
    StringBuilder sb = new StringBuilder();
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = input.read(buffer)) != -1) {
      sb.append(new String(buffer, 0, bytesRead, "UTF-8"));
    }
    return sb.toString();
  }

  /**
   * Read error response body from an HTTP connection.
   */
  public static String readErrorResponse(final HttpURLConnection conn) throws IOException {
    InputStream errStream = conn.getErrorStream();
    if (errStream != null) {
      return readStream(errStream);
    }
    return "";
  }
}
