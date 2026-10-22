package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.KolItemDetail;
import java.math.BigDecimal;
import java.time.Instant;

public record KolItemDetailVO(
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
    Instant createTime,
    String sourceItemUrl,
    long visitCount,
    BigDecimal totalPayAmount,
    BigDecimal refundAmount,
    Instant cleanupTime,
    String cleanupReason,
    String cleanupFailReason) {
  static KolItemDetailVO from(KolItemDetail detail) {
    KolItemVO item = KolItemVO.from(detail.item());
    return new KolItemDetailVO(
        item.id(),
        item.linkCode(),
        item.itemName(),
        item.imageUrl(),
        item.kolId(),
        item.kolCode(),
        item.kolName(),
        item.targetShopId(),
        item.targetShopName(),
        item.targetShopCode(),
        item.targetChannel(),
        item.targetItemId(),
        item.targetItemUrl(),
        item.sourceItemId(),
        item.sourceChannel(),
        item.currentRate(),
        item.salesQuantity(),
        item.payAmount(),
        item.status(),
        item.cleanupStatus(),
        item.createTime(),
        detail.sourceItemUrl(),
        detail.visitCount(),
        detail.totalPayAmount(),
        detail.refundAmount(),
        detail.cleanupTime(),
        detail.cleanupReason(),
        detail.cleanupFailReason());
  }
}
