package com.tcrm.admin.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Field rules shared by the product API and Excel imports (task 05 §4.1–§4.2). */
public final class ProductRules {
  public static final String CODE_PATTERN = "[A-Z0-9_-]{1,16}";
  public static final String WEIDIAN_ID_PATTERN = "[0-9]{1,32}";
  public static final String HTTPS_URL_PATTERN = "https://[^\\s/?#]+\\S*";

  public static final int MAX_SHOP_NAME = 64;
  public static final int MAX_SHOP_REMARK = 500;
  public static final int MAX_CODE = 16;
  public static final int MAX_NAME = 200;
  public static final int MAX_CATEGORY = 64;
  public static final int MAX_IMAGE_URL = 512;

  private static final Pattern CODE = Pattern.compile("^" + CODE_PATTERN + "$");
  private static final Pattern WEIDIAN = Pattern.compile("^" + WEIDIAN_ID_PATTERN + "$");
  private static final Pattern HTTPS = Pattern.compile("^" + HTTPS_URL_PATTERN + "$");
  private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
  private static final Pattern PRICE = Pattern.compile("\\d{1,12}(?:\\.\\d{1,2})?");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private ProductRules() {}

  /**
   * Transaction advisory-lock key for one shop item. An original-product insert and an
   * exclusive-item mapping both take this lock, then recheck the other table.
   *
   * <p>Advisory lock keys in this project:
   *
   * <ul>
   *   <li>{@code crm:shop-item:{shopId}:{weidianItemId}} — this method; {@code
   *       ProductMapper.lockShopItem} hashes it with {@code hashtextextended}
   *   <li>{@code crm_shop:create:{tenantId}} — shop creation for one company; {@code
   *       ShopMapper.lockForCreate} hashes it with {@code hashtextextended}
   * </ul>
   */
  public static String shopItemLockKey(long shopId, String weidianItemId) {
    return "crm:shop-item:" + shopId + ":" + weidianItemId;
  }

  /** Formats a sequence number as a product code: at least 6 digits, zero-padded (P000001). */
  public static String formatCode(long number) {
    return String.format(Locale.ROOT, "P%06d", number);
  }

  public static boolean isCode(String value) {
    return value != null && CODE.matcher(value).matches();
  }

  public static boolean isWeidianId(String value) {
    return value != null && WEIDIAN.matcher(value).matches();
  }

  public static boolean isHttpsUrl(String value) {
    return value != null && HTTPS.matcher(value).matches();
  }

  public static int length(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }

  /**
   * Converts a percentage text (0 to 100, at most 2 decimals, optional trailing "%") to the stored
   * fraction with 4 decimals.
   */
  public static BigDecimal percentToRate(String text) {
    String value = text.endsWith("%") ? text.substring(0, text.length() - 1).strip() : text;
    if (!PERCENT.matcher(value).matches()) {
      return null;
    }
    BigDecimal percent = new BigDecimal(value);
    if (percent.compareTo(HUNDRED) > 0) {
      return null;
    }
    return percent.divide(HUNDRED, 4, RoundingMode.HALF_UP);
  }

  public static String rateText(BigDecimal rate) {
    return rate == null ? null : rate.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  public static String priceText(BigDecimal price) {
    return price == null ? null : price.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Parses a non-negative price; thousands separators are ignored ("1,234.50").
   *
   * @return the price with scale 2, or null when invalid
   */
  public static BigDecimal parsePrice(String text) {
    String value = text.replace(",", "").strip();
    if (!PRICE.matcher(value).matches()) {
      return null;
    }
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
