package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ProductDTO;
import com.tcrm.admin.product.service.ProductRules;
import java.time.Instant;

/**
 * {@code price} 2 decimals in {@code priceCurrency} (ISO 4217, ADR-021); {@code rate} 4-decimal
 * fraction, null when unset.
 */
public record ProductVO(
    Long id,
    String productCode,
    Long shopId,
    String shopName,
    String weidianShopId,
    String weidianItemId,
    String name,
    String category,
    String imageUrl,
    String price,
    String priceCurrency,
    String rate,
    Integer status,
    Instant createTime,
    Instant updateTime) {
  static ProductVO from(ProductDTO p) {
    return new ProductVO(
        p.id(),
        p.productCode(),
        p.shopId(),
        p.shopName(),
        p.weidianShopId(),
        p.weidianItemId(),
        p.name(),
        p.category(),
        p.imageUrl(),
        ProductRules.priceText(p.price()),
        p.priceCurrency(),
        ProductRules.rateText(p.rate()),
        p.status(),
        p.createTime(),
        p.updateTime());
  }
}
