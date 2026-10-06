package com.tcrm.admin.link.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds tracking links and their targets (task 06 §4.1, §4.2). The base URL comes from the
 * required {@code TCRM_LINK_BASE_URL}; startup fails when it is missing or not an https URL without
 * a trailing slash.
 */
@Component
public class LinkUrls {
  /**
   * Weidian page of the exclusive item. Kept as a constant without extra parameters until M0
   * confirms the link format (task 06 §4.2).
   */
  static final String TARGET_URL_PREFIX = "https://weidian.com/item.html?itemID=";

  static final String VARIABLE = "TCRM_LINK_BASE_URL";
  private final String baseUrl;

  public LinkUrls(@Value("${crm.link.base-url}") String baseUrl) {
    this.baseUrl = validate(baseUrl);
  }

  /** {@code {base}/t/{kolCode}/{productCode}}; stable for a KOL and original product. */
  public String linkUrl(String kolCode, String productCode) {
    return baseUrl + "/t/" + kolCode + "/" + productCode;
  }

  /** Target of a link: the Weidian page of the exclusive item. */
  public static String targetUrl(String weidianItemId) {
    return TARGET_URL_PREFIX + weidianItemId;
  }

  /**
   * Returns the value unchanged when it is a valid base URL; throws naming the variable otherwise.
   */
  static String validate(String value) {
    if (isValid(value)) {
      return value;
    }
    throw new IllegalStateException(
        VARIABLE
            + " must be an https URL without a trailing slash, e.g. https://t.example.com (got: "
            + value
            + ")");
  }

  private static boolean isValid(String value) {
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
