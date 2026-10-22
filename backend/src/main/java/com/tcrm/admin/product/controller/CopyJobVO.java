package com.tcrm.admin.product.controller;

import com.tcrm.admin.product.service.CopyJobDTO;
import java.time.Instant;

public record CopyJobVO(
    Long id,
    String jobCode,
    String targetChannel,
    String status,
    int totalCount,
    int successCount,
    int failCount,
    String errorMessage,
    Instant createTime,
    Instant finishTime,
    Long kolId,
    String kolCode,
    String kolName,
    String targetShopCode,
    String targetShopName) {
  static CopyJobVO from(CopyJobDTO row) {
    return new CopyJobVO(
        row.id(),
        row.jobCode(),
        row.targetChannel(),
        row.status(),
        row.totalCount(),
        row.successCount(),
        row.failCount(),
        row.errorMessage(),
        row.createTime(),
        row.finishTime(),
        row.kolId(),
        row.kolCode(),
        row.kolName(),
        row.targetShopCode(),
        row.targetShopName());
  }
}
