package com.tcrm.admin.kol.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds KOL-portal links for invitation and reset mails. The base URL comes from the required
 * {@code TCRM_KOL_PORTAL_BASE_URL} (validated exactly like {@code TCRM_LINK_BASE_URL}); startup
 * fails when it is missing or malformed.
 */
@Component
public class KolPortalUrls {
  static final String VARIABLE = "TCRM_KOL_PORTAL_BASE_URL";
  private final String baseUrl;

  public KolPortalUrls(@Value("${crm.kol-portal.base-url}") String baseUrl) {
    this.baseUrl = validate(baseUrl);
  }

  /** {@code {base}/activate?token=...} for invitation mails (task 12b opens this page). */
  public String activateUrl(String token) {
    return baseUrl + "/activate?token=" + token;
  }

  /** {@code {base}/reset?token=...} for password-reset mails (task 12b opens this page). */
  public String resetUrl(String token) {
    return baseUrl + "/reset?token=" + token;
  }

  /** {@code {base}/register?code=...} for a BD's invite link (task 29). Eight-char code. */
  public String registerUrl(String code) {
    return baseUrl + "/register?code=" + code;
  }

  /** Returns the value unchanged when it is a valid base URL; throws naming the variable. */
  static String validate(String value) {
    if (value != null && !value.isBlank() && !value.endsWith("/") && isValid(value)) {
      return value;
    }
    throw new IllegalStateException(
        VARIABLE
            + " must be an https URL without a trailing slash, e.g. https://kol.example.com (got: "
            + value
            + ")");
  }

  private static boolean isValid(String value) {
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
