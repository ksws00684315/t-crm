package com.tcrm.admin.product.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns source-sheet cells into product fields (ADR-021 §5), following the customer's sheet script
 * 3.0.5 where it applies. Pure functions; nothing here makes network calls, so short links that
 * would need a redirect are reported instead of followed.
 *
 * <p>Lives in {@code product.api} since task 26: the KOL module's promo-sheet reader shares the
 * same link rules. The rules and tests are unchanged (task 26 §3.2).
 */
public final class SheetLinkParser {
  private static final Pattern HYPERLINK =
      Pattern.compile("^=\\s*HYPERLINK\\(\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern IMAGE =
      Pattern.compile("^=\\s*IMAGE\\(\\s*\"(https://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern ITEM_PARAM =
      Pattern.compile("[?&]itemid=(\\d{1,32})(?!\\d)", Pattern.CASE_INSENSITIVE);
  private static final Pattern WEIDIAN_PATH =
      Pattern.compile("/weidian/(\\d{1,32})(?!\\d)", Pattern.CASE_INSENSITIVE);
  private static final Pattern TEN_DIGITS = Pattern.compile("(?<!\\d)(\\d{10})(?!\\d)");
  private static final Pattern SHOP =
      Pattern.compile("shop(\\d{1,32})\\.v\\.weidian\\.com", Pattern.CASE_INSENSITIVE);

  /** Taobao, Tmall or 1688 by host or by an agent's path segment, never by digits in an ID. */
  private static final Pattern OTHER_PLATFORM =
      Pattern.compile(
          "(?:[/.](?:taobao|tmall|1688)\\.com)|(?:/(?:taobao|tmall|1688|ali_1688)/)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)");
  private static final Map<String, String> SYMBOLS =
      Map.of("USD", "$", "CNY", "¥", "EUR", "€", "GBP", "£");

  private SheetLinkParser() {}

  /** Why a non-empty row was not turned into a product row. */
  public enum Skip {
    NON_WEIDIAN,
    NO_ITEM_ID,
    NO_SHOP
  }

  /** Link of a cell: the cell link, then a rich-text run link, a HYPERLINK formula, or the text. */
  public static String link(SheetCellDTO cell) {
    if (cell == null) {
      return null;
    }
    if (cell.hyperlink() != null) {
      return cell.hyperlink();
    }
    if (!cell.runLinks().isEmpty()) {
      return cell.runLinks().getFirst();
    }
    if (cell.formula() != null) {
      Matcher m = HYPERLINK.matcher(cell.formula());
      if (m.find()) {
        return m.group(1);
      }
    }
    String text = cell.text().strip();
    return text.regionMatches(true, 0, "http", 0, 4) ? text : null;
  }

  /** URL-decoded until stable (agents wrap an encoded Weidian URL, sometimes twice). */
  public static String decode(String url) {
    String current = url;
    for (int i = 0; i < 3; i++) {
      String next;
      try {
        next = URLDecoder.decode(current, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        return current;
      }
      if (next.equals(current)) {
        return current;
      }
      current = next;
    }
    return current;
  }

  public static boolean isOtherPlatform(String decoded) {
    return !decoded.toLowerCase(Locale.ROOT).contains("weidian")
        && OTHER_PLATFORM.matcher(decoded).find();
  }

  /**
   * Weidian item ID: {@code itemID=}, then {@code /weidian/{id}}, then the first 10-digit run that
   * is not the shop ID of a {@code shop{id}.v.weidian.com} host.
   */
  public static String itemId(String decoded) {
    for (Pattern p : new Pattern[] {ITEM_PARAM, WEIDIAN_PATH}) {
      Matcher m = p.matcher(decoded);
      if (m.find()) {
        return m.group(1);
      }
    }
    String shop = shopId(decoded);
    Matcher m = TEN_DIGITS.matcher(decoded);
    while (m.find()) {
      if (!m.group(1).equals(shop)) {
        return m.group(1);
      }
    }
    return null;
  }

  /** Weidian shop ID from a {@code shop{id}.v.weidian.com} host, or null. */
  public static String shopId(String decoded) {
    Matcher m = SHOP.matcher(decoded);
    return m.find() ? m.group(1) : null;
  }

  /** HTTPS image address of an {@code =IMAGE("https://…")} formula, or empty. */
  public static String image(SheetCellDTO cell) {
    if (cell == null || cell.formula() == null) {
      return "";
    }
    Matcher m = IMAGE.matcher(cell.formula());
    return m.find() ? m.group(1) : "";
  }

  /**
   * Price text for the import row: the number right after the currency's symbol when the text has
   * it (for example {@code "¥145 | $21.13"} in USD gives {@code 21.13}), otherwise the first
   * number; thousands separators removed. Empty when the cell has no number.
   */
  public static String price(String text, String currency) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String symbol = SYMBOLS.get(currency);
    if (symbol != null) {
      int at = text.indexOf(symbol);
      if (at >= 0) {
        Matcher m = NUMBER.matcher(text.substring(at + symbol.length()).stripLeading());
        if (m.lookingAt()) {
          return m.group(1).replace(",", "");
        }
      }
    }
    Matcher m = NUMBER.matcher(text);
    return m.find() ? m.group(1).replace(",", "") : "";
  }
}
