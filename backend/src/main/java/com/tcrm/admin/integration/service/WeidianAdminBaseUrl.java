package com.tcrm.admin.integration.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WeidianAdminBaseUrl {
  private final String value;

  public WeidianAdminBaseUrl(@Value("${crm.weidian.admin-base-url:}") String value) {
    if (!isValid(value)) {
      throw new IllegalStateException(
          "TCRM_ADMIN_BASE_URL must be an https URL without a trailing slash");
    }
    this.value = value;
  }

  public String callbackUrl() {
    return value + "/admin-api/integration/weidian/oauth/callback";
  }

  static boolean isValid(String value) {
    if (value == null || value.isBlank() || value.endsWith("/")) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
