package com.tcrm.admin.product.service;

/** One copy-job line. {@code seq} is 1-based in creation order and stays stable across pages. */
public record CopyJobItemDTO(
    int seq,
    String sourceChannel,
    String sourceProductId,
    Long sourceShopId,
    String sourceShopName,
    String sourceMerchantNo,
    String targetChannel,
    Long targetShopId,
    String targetShopName,
    String targetItemId,
    String targetItemUrl,
    String status,
    String errorMessage) {}
