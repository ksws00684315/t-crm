package com.tcrm.admin.kol.api;

/**
 * Authenticated copy-token grant for the external copy flow (task 17b). Built only from a valid,
 * non-exhausted, non-expired, non-revoked token whose KOL is enabled.
 */
public record CopyTokenGrantDTO(
    long tokenId,
    long tenantId,
    long kolId,
    String targetChannel,
    Long targetShopId,
    int remaining) {}
