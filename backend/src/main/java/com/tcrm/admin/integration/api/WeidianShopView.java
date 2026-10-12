package com.tcrm.admin.integration.api;

import java.time.Instant;

public record WeidianShopView(
    Long shopId,
    String shopName,
    String weidianShopId,
    String authMode,
    String status,
    Instant authorizedAt,
    Instant accessExpiresAt,
    Instant refreshExpiresAt,
    Instant lastRefreshAt,
    Instant lastCheckAt,
    Boolean lastCheckOk,
    String lastError) {}
