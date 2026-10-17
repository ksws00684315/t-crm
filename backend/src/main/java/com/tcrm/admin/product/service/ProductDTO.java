package com.tcrm.admin.product.service;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDTO(
    Long id,
    String productCode,
    Long shopId,
    String shopName,
    String weidianShopId,
    String weidianItemId,
    String name,
    String category,
    String imageUrl,
    BigDecimal price,
    String priceCurrency,
    BigDecimal rate,
    Integer status,
    Instant createTime,
    Instant updateTime) {}
