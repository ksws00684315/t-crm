package com.tcrm.admin.kol.service;

import java.time.Instant;

/** Public, read-only status and identity fields shown in the creator portal. */
public record KolPortalProfileDTO(
    String systemId,
    String primaryPlatform,
    String primaryHandle,
    String reviewStatus,
    String loginStatus,
    String relationStatus,
    String commissionRate,
    Instant rateEffectiveAt) {}
