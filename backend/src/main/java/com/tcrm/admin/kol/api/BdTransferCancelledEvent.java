package com.tcrm.admin.kol.api;

/**
 * A scheduled transfer was cancelled while it was executing. {@code reasonCode} is a stable token
 * ({@code OWNERSHIP_CHANGED} or {@code TARGET_UNAVAILABLE}), not the stored Chinese sentence.
 */
public record BdTransferCancelledEvent(
    long tenantId, long kolId, long transferId, String kolCode, String reasonCode) {}
