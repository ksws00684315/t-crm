package com.tcrm.admin.order.service;

import java.time.Instant;

/**
 * One shop of the current company with its order-sync status (task 19a §4.1.2). Shops without a
 * sync record yet still appear; {@code lastSuccessAt} and {@code lastError} are then null.
 *
 * @param shopId crm_shop.id
 * @param shopName shop display name
 * @param enabled whether the shop is enabled
 * @param authorized whether Weidian authorization currently covers the shop
 * @param lastSuccessAt when the shop's orders and refunds last fully synced, null when never
 * @param lastError safe English summary of the latest failure, null when the last run succeeded
 */
public record OrderSyncStateDTO(
    Long shopId,
    String shopName,
    boolean enabled,
    boolean authorized,
    Instant lastSuccessAt,
    String lastError) {}
