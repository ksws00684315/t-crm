package com.tcrm.admin.integration.service;

/** Internal token pair whose string representation never reveals the access token. */
public final class WeidianToken {
  private final String appKey;
  private final String accessToken;

  public WeidianToken(String appKey, String accessToken) {
    this.appKey = appKey;
    this.accessToken = accessToken;
  }

  public String appKey() {
    return appKey;
  }

  public String accessToken() {
    return accessToken;
  }

  @Override
  public String toString() {
    return "WeidianToken[redacted]";
  }
}
