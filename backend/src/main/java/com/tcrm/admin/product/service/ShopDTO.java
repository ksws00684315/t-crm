package com.tcrm.admin.product.service;

import java.time.Instant;

public record ShopDTO(
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
    ApiChecksStatus apiStatus,
    boolean copyEligible,
    String copyIneligibleReason,
    Instant createTime,
    Instant updateTime) {
  public ShopDTO(
      Long id,
      String shopName,
      String weidianShopId,
      Integer status,
      Boolean ignoredCopyTarget,
      String remark,
      Instant createTime,
      Instant updateTime) {
    this(
        id,
        null,
        shopName,
        "WEIDIAN",
        weidianShopId,
        status,
        remark,
        false,
        "EXITED",
        20000,
        0,
        null,
        "UNAUTHORIZED",
        null,
        new ApiChecksStatus(
            new ApiStatus(null, null, null),
            new ApiStatus(null, null, null),
            new ApiStatus(null, null, null)),
        false,
        "UNAUTHORIZED",
        createTime,
        updateTime);
  }

  public record ApiStatus(Boolean ok, Instant checkedAt, String error) {}

  public record ApiChecksStatus(ApiStatus product, ApiStatus order, ApiStatus refund) {}
}
