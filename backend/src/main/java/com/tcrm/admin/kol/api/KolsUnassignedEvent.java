package com.tcrm.admin.kol.api;

/**
 * Creators under a disabled BD became unassigned. Published only when {@code count} is positive.
 */
public record KolsUnassignedEvent(long tenantId, long bdUserId, String bdName, int count) {}
