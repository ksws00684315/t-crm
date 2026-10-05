package com.tcrm.redirect.service;

import com.tcrm.redirect.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
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
    if (this.fallback == null || salt.isBlank()) {
      throw new IllegalArgumentException(
          "An HTTP(S) fallback URL and nonempty IP hash salt are required");
    }
  }

  public ResponseEntity<Void> redirect(
      String kolCode, String productCode, HttpServletRequest request) {
    if (!kolCode.matches("^KOL\\d{4,8}$") || !productCode.matches("^[A-Za-z0-9_-]{1,32}$")) {
      return ResponseEntity.notFound().build();
    }
    String value;
    try {
      value = redis.lookup(kolCode, productCode);
    } catch (RuntimeException e) {
      LOG.error("Link lookup failed", e);
      return redirectTo(fallback);
    }
    // TODO(task-01): Fetch missing indexes once the planned internal link API exists.
    if (value == null) {
      return redirectTo(fallback);
    }
    var entry = LinkEntry.parse(value);
    URI target = entry.map(link -> httpUri(link.targetUrl())).orElse(null);
    if (target == null) {
      return redirectTo(fallback);
    }
    var event =
        Map.of(
            "kolItemId",
            entry.get().kolItemId(),
            "kolCode",
            kolCode,
            "productCode",
            productCode,
            "ts",
            Long.toString(System.currentTimeMillis()),
            "ipHash",
            hash(clientIps.resolve(request)),
            "ua",
            header(request, "User-Agent", 512),
            "ref",
            header(request, "Referer", 512),
            "country",
            header(request, "CF-IPCountry", 8));
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
