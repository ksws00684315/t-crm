package com.tcrm.admin.kol.service;

import java.time.Instant;

/**
 * Creator-portal profile projection behind GET/PUT /kol-api/me (task 64). Commission fields stay
 * null until task 62 provides them through the commission {@code api} package.
 */
public record KolPortalProfileDTO(
    String kolCode,
    String primaryPlatform,
    String primaryHandle,
    String reviewStatus,
    String loginStatus,
    String relationStatus,
    String commissionRate,
    Instant rateEffectiveAt) {}
