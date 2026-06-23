package com.nexus.artifacts.promotion.service;

import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection SSL helper for self-signed certificate support.
 *
 * <p>Unlike {@code HttpsURLConnection.setDefaultSSLSocketFactory()} which affects
 * the entire JVM process, this helper applies trust-all SSL configuration only
 * to individual connections, isolating the security impact.
 *
 * <p>Usage: call {@link #applyTrustAllSsl(HttpURLConnection)} after opening
 * a connection that may target a server with a self-signed certificate.
 */
public class SslHelper {

  private static final Logger log = LoggerFactory.getLogger(SslHelper.class);

  private static final TrustManager[] TRUST_ALL_CERTS = new TrustManager[]{
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
      }
  };

  private static final HostnameVerifier TRUST_ALL_HOSTNAME = (hostname, session) -> true;

  private static volatile SSLContext trustAllSslContext;

  private SslHelper() { }

  /**
   * Get or lazily initialize the trust-all SSLContext (singleton).
   */
  public static SSLContext getTrustAllSslContext() {
    if (trustAllSslContext == null) {
      synchronized (SslHelper.class) {
        if (trustAllSslContext == null) {
          try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, TRUST_ALL_CERTS, new SecureRandom());
            trustAllSslContext = sc;
            log.info("SslHelper: trust-all SSLContext initialized (per-connection mode, not global)");
          }
          catch (Exception e) {
            log.warn("SslHelper: failed to initialize trust-all SSLContext: {}", e.getMessage(), e);
            return null;
          }
        }
      }
    }
    return trustAllSslContext;
  }

  /**
   * Apply trust-all SSL configuration to an HTTPS connection.
   * No-op for non-HTTPS connections.
   *
   * <p>This method only affects the given connection instance, not the JVM-global defaults.
   *
   * @param conn the HTTP connection to configure
   */
  public static void applyTrustAllSsl(final HttpURLConnection conn) {
    if (!(conn instanceof HttpsURLConnection)) {
      return;
    }
    SSLContext sc = getTrustAllSslContext();
    if (sc != null) {
      ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
      ((HttpsURLConnection) conn).setHostnameVerifier(TRUST_ALL_HOSTNAME);
    }
  }
}
