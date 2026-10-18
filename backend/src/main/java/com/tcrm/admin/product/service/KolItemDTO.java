package com.tcrm.admin.product.service;

import java.time.Instant;

public record KolItemDTO(
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
    String itemName,
    String source,
    Instant validFrom,
    Instant validTo) {}
