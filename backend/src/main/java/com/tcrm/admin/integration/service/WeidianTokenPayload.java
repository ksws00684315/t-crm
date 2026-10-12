package com.tcrm.admin.integration.service;

import java.time.Instant;

/** In-memory OAuth result; string output intentionally excludes every credential. */
public final class WeidianTokenPayload {
  private final String accessToken;
  private final String refreshToken;
  private final Instant accessExpiresAt;
  private final Instant refreshExpiresAt;
  private final String openid;

  public WeidianTokenPayload(
      String accessToken,
      String refreshToken,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      String openid) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.accessExpiresAt = accessExpiresAt;
    this.refreshExpiresAt = refreshExpiresAt;
    this.openid = openid;
  }

  public String accessToken() {
    return accessToken;
  }

  public String refreshToken() {
    return refreshToken;
  }

  public Instant accessExpiresAt() {
    return accessExpiresAt;
  }

  public Instant refreshExpiresAt() {
    return refreshExpiresAt;
  }

  public String openid() {
    return openid;
  }

  @Override
  public String toString() {
    return "WeidianTokenPayload[redacted]";
  }
}
