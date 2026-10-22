package com.tcrm.admin.system.model;

import java.util.Arrays;
import java.util.List;

/** Complete registry for the editable, non-secret platform configuration (task 34 §3.1). */
public enum SystemConfigDefinition {
  SITE_NAME("site.name", "site", "string", null, "SparkTrack", 1, 32),
  MAIL_FROM("mail.from", "site", "string", "crm.mail.from", "", null, 255),
  KOL_PORTAL_URL("url.kol-portal", "url", "url", "crm.kol-portal.base-url", "", null, null),
  LINK_URL("url.link", "url", "url", "crm.link.base-url", "", null, null),
  ADMIN_URL("url.admin", "url", "url", "crm.weidian.admin-base-url", "", null, null),
  UPLOAD_URL("url.upload", "url", "url", "crm.upload.base-url", "", null, null),
  REDIRECT_FALLBACK_URL(
      "url.redirect-fallback", "url", "url", "crm.redirect.fallback-url", "", null, null),
  COPY_TOKEN_DEFAULT_QUOTA(
      "copy-token.default-quota",
      "business",
      "integer",
      "crm.copy-token.default-quota",
      "1",
      1,
      100000),
  COPY_TOKEN_DEFAULT_VALID_MINUTES(
      "copy-token.default-valid-minutes",
      "business",
      "minutes",
      "crm.copy-token.default-valid-minutes",
      "1440",
      5,
      10080),
  COPY_TOKEN_MAX_QUOTA("copy-token.max-quota", "business", "integer", null, "100000", 1, 100000),
  COPY_TOKEN_MAX_VALID_MINUTES(
      "copy-token.max-valid-minutes", "business", "minutes", null, "10080", 5, 10080),
  SHOP_MAX_PER_COMPANY("shop.max-per-company", "business", "integer", null, "20", 1, 1000),
  PAYOUT_ACCOUNT_MAX_PER_KOL("payout-account.max-per-kol", "business", "integer", null, "5", 1, 20),
  KOL_INVITE_TTL_HOURS("kol.invite-ttl-hours", "business", "hours", null, "72", 1, 72),
  OPER_LOG_RETENTION_DAYS("oper-log.retention-days", "business", "days", null, "180", 180, 3650),
  /** 0 = automatic cleanup off (task 40 owner decision 2026-10-22). */
  ORDER_SYNC_FAILURE_RETENTION_DAYS(
      "order.sync-failure.retention-days", "business", "days", null, "0", 0, 3650),
  LOGIN_PAIR_LIMIT("login.fail.pair-limit", "security", "integer", null, "5", 3, 1000),
  LOGIN_IP_LIMIT("login.fail.ip-limit", "security", "integer", null, "20", 3, 10000),
  LOGIN_ACCOUNT_LIMIT("login.fail.account-limit", "security", "integer", null, "50", 3, 10000),
  LOGIN_WINDOW_MINUTES("login.fail.window-minutes", "security", "minutes", null, "15", 5, 1440),
  CAPTCHA_ISSUE_PER_MINUTE(
      "captcha.issue-per-minute",
      "security",
      "integer",
      "crm.auth.captcha.issue-per-minute",
      "120",
      10,
      1000),
  KOL_REGISTER_IP_PER_HOUR("kol.register.ip-per-hour", "security", "integer", null, "20", 1, 1000),
  KOL_REGISTER_PREVIEW_IP_PER_HOUR(
      "kol.register-preview.ip-per-hour", "security", "integer", null, "20", 1, 1000),
  KOL_FORGOT_EMAIL_LIMIT("kol.forgot.email-limit", "security", "integer", null, "3", 1, 1000),
  KOL_FORGOT_EMAIL_WINDOW_MINUTES(
      "kol.forgot.email-window-minutes", "security", "minutes", null, "10", 5, 1440),
  KOL_FORGOT_IP_LIMIT("kol.forgot.ip-limit", "security", "integer", null, "20", 1, 1000),
  KOL_FORGOT_IP_WINDOW_MINUTES(
      "kol.forgot.ip-window-minutes", "security", "minutes", null, "60", 5, 1440),
  PRODUCT_LINKS_IP_PER_MINUTE(
      "product-links.ip-per-minute",
      "security",
      "integer",
      "crm.product-links.ip-rate-per-minute",
      "120",
      1,
      10000),
  PRODUCT_LINKS_CONVERT_TOKEN_PER_MINUTE(
      "product-links.convert-token-per-minute",
      "security",
      "integer",
      "crm.product-links.convert-token-rate-per-minute",
      "10",
      1,
      10000),
  PRODUCT_LINKS_RESOLVE_TOKEN_PER_MINUTE(
      "product-links.resolve-token-per-minute",
      "security",
      "integer",
      "crm.product-links.resolve-token-rate-per-minute",
      "120",
      1,
      10000),
  WEIDIAN_QPS("weidian.qps", "security", "integer", "crm.weidian.qps", "5", 1, 50);

  private final String key;
  private final String group;
  private final String type;
  private final String property;
  private final String fallback;
  private final Integer min;
  private final Integer max;

  SystemConfigDefinition(
      String key,
      String group,
      String type,
      String property,
      String fallback,
      Integer min,
      Integer max) {
    this.key = key;
    this.group = group;
    this.type = type;
    this.property = property;
    this.fallback = fallback;
    this.min = min;
    this.max = max;
  }

  public String key() {
    return key;
  }

  public String group() {
    return group;
  }

  public String type() {
    return type;
  }

  public String property() {
    return property;
  }

  public String fallback() {
    return fallback;
  }

  public Integer min() {
    return min;
  }

  public Integer max() {
    return max;
  }

  public String nameKey() {
    return "systemConfig.items." + key.replace('.', '_').replace('-', '_');
  }

  public static SystemConfigDefinition find(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst().orElse(null);
  }

  public static List<SystemConfigDefinition> list() {
    return List.of(values());
  }
}
