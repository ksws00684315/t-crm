package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.CopyJobItemDTO;

public record CopyJobItemVO(
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
    String errorMessage) {
  static CopyJobItemVO from(CopyJobItemDTO row) {
    return new CopyJobItemVO(
        row.seq(),
        row.sourceChannel(),
        row.sourceProductId(),
        row.sourceShopId(),
        row.sourceShopName(),
        row.sourceMerchantNo(),
        row.targetChannel(),
        row.targetShopId(),
        row.targetShopName(),
        row.targetItemId(),
        row.targetItemUrl(),
        row.status(),
        row.errorMessage());
  }
}
