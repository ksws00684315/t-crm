package com.tcrm.admin.order.service;

import java.time.Instant;

/** Validated controller filters, with no user or tenant identity supplied by the caller. */
public record OrderFilterDTO(
    Long shopId,
    Instant payFrom,
    Instant payTo,
    Instant orderTimeFrom,
    Instant orderTimeTo,
    String kolCode,
    String kolKeyword,
    String status,
    String attribution,
    String refundState,
    boolean abnormal,
    String orderNo,
    String channel,
    int page,
    int size) {}
