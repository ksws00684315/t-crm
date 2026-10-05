package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ShopDTO;
import java.time.Instant;

public record ShopVO(
    Long id,
    String shopName,
    String weidianShopId,
    Integer status,
    String remark,
    Instant createTime,
    Instant updateTime) {
  static ShopVO from(ShopDTO s) {
    return new ShopVO(
        s.id(),
        s.shopName(),
        s.weidianShopId(),
        s.status(),
        s.remark(),
        s.createTime(),
        s.updateTime());
  }
}
