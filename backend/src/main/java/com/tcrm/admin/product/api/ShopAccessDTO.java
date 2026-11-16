package com.tcrm.admin.product.api;

import java.time.Instant;

/** Shop fields other modules need without depending on the product package internals. */
public record ShopAccessDTO(
    Long id,
    String name,
    String weidianShopId,
    Integer status,
    String shopCode,
    Instant createdAt) {
  public ShopAccessDTO(
      Long id, String name, String weidianShopId, Integer status, String shopCode) {
    this(id, name, weidianShopId, status, shopCode, null);
  }
}
