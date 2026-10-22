package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.KolItemView;
import java.math.BigDecimal;
import java.time.Instant;

public record KolItemVO(
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
    Instant createTime) {
  static KolItemVO from(KolItemView view) {
    return new KolItemVO(
        view.id(),
        view.linkCode(),
        view.itemName(),
        view.imageUrl(),
        view.kolId(),
        view.kolCode(),
        view.kolName(),
        view.targetShopId(),
        view.targetShopName(),
        view.targetShopCode(),
        view.targetChannel(),
        view.targetItemId(),
        view.targetItemUrl(),
        view.sourceItemId(),
        view.sourceChannel(),
        view.currentRate(),
        view.salesQuantity(),
        view.payAmount(),
        view.status(),
        view.cleanupStatus(),
        view.createTime());
  }
}
