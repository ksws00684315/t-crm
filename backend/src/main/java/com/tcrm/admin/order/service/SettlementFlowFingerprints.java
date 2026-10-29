package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Deduplication fingerprint of one Weidian settlement flow (task 76 §3.2). The API has no flow id,
 * so the key is a SHA-256 over shop + queryType + orderId + refundId + tradeType + tradeSubType +
 * tradeTime + raw amount + remark. Known limitation (design §5.2): two flows whose fields are all
 * equal merge into one row.
 */
final class SettlementFlowFingerprints {

  private SettlementFlowFingerprints() {}

  static String of(
      long shopId,
      String queryType,
      String orderId,
      String refundId,
      String tradeType,
      String tradeSubType,
      Instant tradeTime,
      BigDecimal amountRaw,
      String remark) {
    String canonical =
        String.join(
            "|",
            field(String.valueOf(shopId)),
            field(queryType),
            field(orderId),
            field(refundId),
            field(tradeType),
            field(tradeSubType),
            field(tradeTime == null ? "" : String.valueOf(tradeTime.toEpochMilli())),
            field(amountRaw == null ? "" : amountRaw.stripTrailingZeros().toPlainString()),
            field(remark));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Length-prefixed field so a separator inside one value can never shift the boundaries. */
  private static String field(String value) {
    String text = value == null ? "" : value;
    return text.length() + ":" + text;
  }
}
