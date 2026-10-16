package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolRefDTO;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds tracking links and their targets (task 06 §4.1, §4.2; task 11 §4.4). The base URL comes
 * from the required {@code TCRM_LINK_BASE_URL}; startup fails when it is missing or not an https
 * URL without a trailing slash.
 */
@Component
public class LinkUrls {
  /**
   * Weidian page of the exclusive item. Kept as a constant without extra parameters until M0
   * confirms the link format (task 06 §4.2).
   */
  static final String TARGET_URL_PREFIX = "https://weidian.com/item.html?itemID=";

  static final String PRODUCT_VARIABLE = "{PRODUCT_ID}";
  static final String INVITE_VARIABLE = "{INVITE_CODE}";
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
   * Target of a link for a KOL with agent platform settings (task 11 §4.4; design §4.4): when the
   * KOL has an <em>enabled</em> template and the template needs no invite code, or the KOL has one,
   * the template is filled with the URL-encoded invite code and the exclusive item ID; otherwise
   * the link falls back to the Weidian page. This one rule feeds both {@code link:{kolCode}} and
   * {@code linkitem:{nn}} values and the link list's target address.
   *
   * @param kol KOL with the resolved template ({@code linkTemplateUrl} null without a template)
   * @param weidianItemId exclusive item Weidian ID
   */
  public static String targetUrl(KolRefDTO kol, String weidianItemId) {
    String template = kol.linkTemplateUrl();
    if (template != null && kol.linkTemplateEnabled()) {
      boolean needsInvite = template.contains(INVITE_VARIABLE);
      if (!needsInvite || kol.inviteCode() != null) {
        String url =
            needsInvite ? template.replace(INVITE_VARIABLE, urlEncode(kol.inviteCode())) : template;
        return url.replace(PRODUCT_VARIABLE, weidianItemId);
      }
    }
    return TARGET_URL_PREFIX + weidianItemId;
  }

  /** Percent-encoding for the invite code (defensive: valid codes contain URL-safe chars only). */
  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
