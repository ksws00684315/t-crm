package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.KolItemDTO;
import java.time.Instant;

public record KolItemVO(
    Long id,
    Long kolId,
    String kolCode,
    String kolName,
    Long productId,
    String productCode,
    String productName,
    Long shopId,
    String shopName,
    String weidianShopId,
    String weidianItemId,
    Instant validFrom,
    Instant validTo) {
  static KolItemVO from(KolItemDTO d) {
    return new KolItemVO(
        d.id(),
        d.kolId(),
        d.kolCode(),
        d.kolName(),
        d.productId(),
        d.productCode(),
        d.productName(),
        d.shopId(),
        d.shopName(),
        d.weidianShopId(),
        d.weidianItemId(),
        d.validFrom(),
        d.validTo());
  }
}
