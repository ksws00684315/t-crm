package com.tcrm.admin.product.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the creator-item list (task 39). {@code targetShopCode} comes from the target shop row
 * when present.
 */
public record KolItemView(
    Long id,
    String linkCode,
    String itemName,
    String imageUrl,
    Long kolId,
    String kolCode,
    String kolName,
    Long targetShopId,
    String targetShopName,
    String targetShopCode,
    String targetChannel,
    String targetItemId,
    String targetItemUrl,
    String sourceItemId,
    String sourceChannel,
    String currentRate,
    long salesQuantity,
    BigDecimal payAmount,
    String status,
    String cleanupStatus,
    Instant createTime) {}
