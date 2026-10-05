package com.tcrm.admin.shared;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public CorsConfiguration(@Value("${crm.cors.allowed-origins:}") String origins) {
    this.origins =
        Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    if (Arrays.stream(this.origins).anyMatch(origin -> !validOrigin(origin))) {
      throw new IllegalArgumentException(
          "CORS origins must be explicit HTTP(S) origins without paths or wildcards");
    }
  }

  private static boolean validOrigin(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null
          && uri.getPath().isEmpty();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (origins.length > 0) {
      registry
          .addMapping("/admin-api/**")
          .allowedOrigins(origins)
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
          .allowedHeaders("Content-Type", "X-Admin-Token")
          .exposedHeaders("X-Trace-Id");
    }
  }
}
