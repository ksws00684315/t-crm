package com.tcrm.admin.product.service;

import java.time.Instant;

public record ShopDTO(
    Long id,
    String shopName,
    String weidianShopId,
    Integer status,
    String remark,
    Instant createTime,
    Instant updateTime) {}
