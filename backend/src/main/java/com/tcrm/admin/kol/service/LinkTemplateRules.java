package com.tcrm.admin.kol.service;

import java.util.regex.Pattern;

/** Link template field rules shared by the API and the KOL import (task 11 §4.2, §4.3). */
public final class LinkTemplateRules {
  /** Template code: lower-case letters, digits and hyphens (same as the database CHECK). */
  public static final String CODE_PATTERN = "[a-z0-9-]{2,32}";

  /** https URL that contains {@code {PRODUCT_ID}}; {@code {INVITE_CODE}} is optional. */
  public static final String URL_PATTERN = "https://\\S*\\{PRODUCT_ID\\}\\S*";

  public static final int MAX_CODE = 32;
  public static final int MAX_NAME = 64;
  public static final int MAX_URL = 512;

  private static final Pattern CODE = Pattern.compile(CODE_PATTERN);
  private static final Pattern URL = Pattern.compile(URL_PATTERN);
  private static final String INVITE_VAR = "{INVITE_CODE}";

  private LinkTemplateRules() {}

  public static boolean isCode(String value) {
    return value != null && CODE.matcher(value).matches();
  }

  /** https URL that contains {@code {PRODUCT_ID}}; {@code {INVITE_CODE}} is optional. */
  public static boolean isUrl(String value) {
    return value != null && URL.matcher(value).matches();
  }

  /** True when the template needs the invite code, so the KOL must have one. */
  public static boolean needsInviteCode(String templateUrl) {
    return templateUrl != null && templateUrl.contains(INVITE_VAR);
  }
}
