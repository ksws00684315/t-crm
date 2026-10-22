package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.ShopDTO;
import java.time.Instant;

public record ShopVO(
    Long id,
    String shopCode,
    String shopName,
    String channel,
    String weidianShopId,
    Integer status,
    String remark,
    boolean isSource,
    String copyPoolStatus,
    Integer maxItems,
    Integer currentItems,
    Instant itemsRefreshedAt,
    String authStatus,
    Instant authExpiresAt,
    ShopDTO.ApiChecksStatus apiStatus,
    boolean copyEligible,
    String copyIneligibleReason,
    Instant createTime,
    Instant updateTime) {
  static ShopVO from(ShopDTO s) {
    return new ShopVO(
        s.id(),
        s.shopCode(),
        s.shopName(),
        s.channel(),
        s.weidianShopId(),
        s.status(),
        s.remark(),
        s.isSource(),
        s.copyPoolStatus(),
        s.maxItems(),
        s.currentItems(),
        s.itemsRefreshedAt(),
        s.authStatus(),
        s.authExpiresAt(),
        s.apiStatus(),
        s.copyEligible(),
        s.copyIneligibleReason(),
        s.createTime(),
        s.updateTime());
  }
}
