package com.tcrm.redirect.service;

import com.tcrm.redirect.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class RedirectService {
  private static final Logger LOG = LoggerFactory.getLogger(RedirectService.class);
  private final ClickRedis redis;
  private final ClientIpResolver clientIps;
  private final URI fallback;
  private final String salt;

  public RedirectService(
      ClickRedis redis,
      ClientIpResolver clientIps,
      @Value("${crm.redirect.fallback-url}") String fallback,
      @Value("${crm.redirect.ip-hash-salt}") String salt) {
    this.redis = redis;
    this.clientIps = clientIps;
    this.fallback = httpUri(fallback);
    this.salt = salt;
    if (this.fallback == null) {
      throw new IllegalArgumentException("An HTTP(S) fallback URL is required");
    }
    if (salt == null || salt.length() < 32) {
      throw new IllegalArgumentException(
          "TCRM_IP_HASH_SALT must be at least 32 characters; generate one with: openssl rand -hex 32");
    }
  }

  public ResponseEntity<Void> redirect(
      String kolCode, String productCode, HttpServletRequest request) {
    if (!kolCode.matches("^KOL\\d{4,8}$") || !productCode.matches("^[A-Za-z0-9_-]{1,32}$")) {
      return ResponseEntity.notFound().build();
    }
    return resolve(
        () -> redis.lookup(kolCode, productCode),
        Map.of("kolCode", kolCode, "productCode", productCode),
        request);
  }

  /** Redirects by copied Weidian item ID ({@code /i/{weidianItemId}}, ADR-019). */
  public ResponseEntity<Void> redirectItem(String weidianItemId, HttpServletRequest request) {
    if (!weidianItemId.matches("^[0-9]{1,20}$")) {
      return redirectTo(fallback);
    }
    return resolve(() -> redis.lookupItem(weidianItemId), Map.of(), request);
  }

  /**
   * Looks up one index value and redirects to its target, recording the click; any failure, miss,
   * malformed or inactive value redirects to the fallback without a click.
   */
  private ResponseEntity<Void> resolve(
      Supplier<String> lookup, Map<String, String> linkFields, HttpServletRequest request) {
    String value;
    try {
      value = lookup.get();
    } catch (RuntimeException e) {
      LOG.error("Link lookup failed", e);
      return redirectTo(fallback);
    }
    // A miss means the link does not exist: the index is kept complete by crm-admin (incremental
    // updates plus a daily rebuild), so there is no fallback to the backend (ADR-012).
    if (value == null) {
      return redirectTo(fallback);
    }
    var entry = LinkEntry.parse(value);
    URI target = entry.map(link -> httpUri(link.targetUrl())).orElse(null);
    if (target == null) {
      return redirectTo(fallback);
    }
    var event = new HashMap<>(linkFields);
    event.put("kolItemId", entry.get().kolItemId());
    event.put("ts", Long.toString(System.currentTimeMillis()));
    event.put("ipHash", hash(clientIps.resolve(request)));
    event.put("ua", header(request, "User-Agent", 512));
    event.put("ref", header(request, "Referer", 512));
    event.put("country", header(request, "CF-IPCountry", 8));
    try {
      redis.append(event);
    } catch (RuntimeException e) {
      LOG.error("Click stream append failed", e);
    }
    return redirectTo(target);
  }

  public ResponseEntity<Void> health() {
    try {
      return ResponseEntity.status(redis.healthy() ? 200 : 503).build();
    } catch (RuntimeException e) {
      return ResponseEntity.status(503).build();
    }
  }

  private ResponseEntity<Void> redirectTo(URI target) {
    return ResponseEntity.status(302).location(target).header("Cache-Control", "no-store").build();
  }

  private String hash(String ip) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((salt + ip).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String header(HttpServletRequest request, String name, int max) {
    String value = request.getHeader(name);
    return value == null ? "" : value.substring(0, Math.min(max, value.length()));
  }

  private static URI httpUri(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
              && uri.getHost() != null
              && uri.getUserInfo() == null
          ? uri
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
