package com.tcrm.admin.order.service;

import java.time.Instant;

/** Validated controller filters, with no user or tenant identity supplied by the caller. */
public record OrderFilterDTO(
    Long shopId,
    Instant payFrom,
    Instant payTo,
    String kolCode,
    String status,
    String attribution,
    int page,
    int size) {}
