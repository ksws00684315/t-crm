package com.tcrm.admin.kol.api;

import java.time.Instant;

/**
 * A KOL currently in {@code PROMOTING}, with the latest time it entered that status (or a fallback
 * timestamp when no STATUS record exists).
 */
public record PromotingKolDTO(
    Long id,
    String kolCode,
    String name,
    Long bdUserId,
    Instant lastPromotingAt,
    Instant fallbackIdleAt) {}
