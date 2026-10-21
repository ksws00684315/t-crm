package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.api.SystemConfigApi;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class WeidianAdminBaseUrl {
  private final SystemConfigApi configs;

  public WeidianAdminBaseUrl(SystemConfigApi configs) {
    this.configs = configs;
  }

  public String callbackUrl() {
    return configs.getValue("url.admin") + "/admin-api/integration/weidian/oauth/callback";
  }

  static boolean isValid(String value) {
    if (value == null || value.isBlank() || value.endsWith("/")) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      boolean localHttp =
          "http".equals(uri.getScheme())
              && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
      return ("https".equals(uri.getScheme()) || localHttp)
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
