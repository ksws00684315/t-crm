package com.tcrm.admin.link.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One parsed {@code stream:click} message written by the redirect service: fields {@code
 * kolItemId}, {@code ts} (epoch milliseconds), {@code ipHash}, {@code ua}, {@code ref}, {@code
 * country} (plus {@code kolCode}/{@code productCode}, which are not stored).
 */
public record ClickEvent(
    String messageId,
    long id,
    long clickMillis,
    long kolItemId,
    String ipHash,
    String ua,
    String referer,
    String country) {
  static final int UA_LENGTH = 512;
  static final int REFERER_LENGTH = 512;
  static final int COUNTRY_LENGTH = 8;
  private static final Pattern IP_HASH = Pattern.compile("^[0-9a-fA-F]{64}$");
  private static final Pattern POSITIVE = Pattern.compile("^[1-9]\\d{0,18}$");

  /** Empty when the message cannot be stored (bad ID, missing or malformed required fields). */
  public static Optional<ClickEvent> parse(String messageId, Map<String, String> fields) {
    var id = ClickRules.clickId(messageId);
    if (id.isEmpty() || fields == null) {
      return Optional.empty();
    }
    Long kolItemId = positive(fields.get("kolItemId"));
    Long ts = positive(fields.get("ts"));
    String ipHash = fields.get("ipHash");
    if (kolItemId == null || ts == null || ipHash == null || !IP_HASH.matcher(ipHash).matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new ClickEvent(
            messageId,
            id.getAsLong(),
            ts,
            kolItemId,
            ipHash.toLowerCase(Locale.ROOT),
            ClickRules.truncate(fields.get("ua"), UA_LENGTH),
            ClickRules.truncate(fields.get("ref"), REFERER_LENGTH),
            ClickRules.truncate(fields.get("country"), COUNTRY_LENGTH)));
  }

  /** Redis key holding the event time of the previous click of this link and IP hash. */
  public String lastClickKey() {
    return "click:last:" + kolItemId + ":" + ipHash;
  }

  private static Long positive(String value) {
    if (value == null || !POSITIVE.matcher(value).matches()) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
