package com.tcrm.admin;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared test-only Weidian configuration using a fresh random AES key for each test JVM. */
public final class WeidianTestProperties {
  private static final String SECRET_KEY = randomSecretKey();

  private WeidianTestProperties() {}

  public static void register(DynamicPropertyRegistry registry) {
    register(registry, "https://api.example.test/api", "https://oauth.example.test");
  }

  public static void register(
      DynamicPropertyRegistry registry, String apiBaseUrl, String oauthBaseUrl) {
    registry.add("crm.weidian.secret-key", () -> SECRET_KEY);
    registry.add("TCRM_IP_HASH_SALT", () -> SECRET_KEY);
    registry.add("crm.weidian.admin-base-url", () -> "https://admin.example.test");
    registry.add("crm.weidian.api-base-url", () -> apiBaseUrl);
    registry.add("crm.weidian.oauth-base-url", () -> oauthBaseUrl);
  }

  private static String randomSecretKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
