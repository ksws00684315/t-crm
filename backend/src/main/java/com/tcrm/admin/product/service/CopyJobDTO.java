package com.tcrm.admin.product.service;

import java.time.Instant;

/**
 * One product-task row. {@code targetShopCode} stays empty until task 37 adds shop codes; the page
 * keeps the column hidden.
 */
public record CopyJobDTO(
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
    String targetShopName) {}
