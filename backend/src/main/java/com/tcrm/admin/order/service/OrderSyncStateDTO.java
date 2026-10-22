package com.tcrm.admin.order.service;

import java.time.Instant;
import java.util.List;

/**
 * One shop of the current company with its order-sync status (task 19a §4.1.2, extended by task 40
 * §3.5). Shops without a sync record still appear; {@code lastSuccessAt} and {@code lastError} are
 * then null.
 *
 * @param shopId crm_shop.id
 * @param shopName shop display name
 * @param enabled whether the shop is enabled
 * @param authorized whether Weidian authorization currently covers the shop
 * @param lastSuccessAt when the shop's orders and refunds last fully synced, null when never
 * @param lastError safe English summary of the latest failure, null when the last run succeeded
 * @param orders order-kind monitoring: provenance, failures, missing fields
 * @param refunds refund-kind monitoring: provenance, failures, missing fields
 * @param lastImport the shop's latest import with its declared data cutoff; null when never
 */
public record OrderSyncStateDTO(
    Long shopId,
    String shopName,
    boolean enabled,
    boolean authorized,
    Instant lastSuccessAt,
    String lastError,
    KindSyncDTO orders,
    KindSyncDTO refunds,
    ImportCutoffDTO lastImport) {

  /**
   * Per-kind monitoring (task 40 §3.5).
   *
   * @param source where the shop's latest data came from: API (1) or IMPORT (2); null when none
   * @param lastSuccessAt shared per-shop success time (orders and refunds sync atomically)
   * @param lastError shared per-shop last error
   * @param failures24h failures of this kind in the last 24 hours
   * @param missingFields counts of records missing critical fields, by field name; the field
   *     dictionary lives in design §4.6a
   */
  public record KindSyncDTO(
      String source,
      Instant lastSuccessAt,
      String lastError,
      long failures24h,
      java.util.Map<String, Long> missingFields) {}

  /**
   * The shop's latest import (task 40 §3.5, row 09.03).
   *
   * @param kind ORDER or REFUND
   * @param dataCutoffTime the declared data cutoff of that import
   * @param taskId crm_task.id of that import
   */
  public record ImportCutoffDTO(String kind, Instant dataCutoffTime, Long taskId) {}

  /** Fixed field-name list of {@code missingFields} (design §4.6a). */
  public static final List<String> MISSING_FIELD_KEYS =
      List.of(
          "payTime",
          "orderTime",
          "payAmount",
          "totalAmount",
          "itemItemId",
          "itemPayAmount",
          "orderItemId",
          "finishTime");
}
