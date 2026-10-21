package com.tcrm.admin.system.api;

import java.time.Instant;

/**
 * A departure plan was executed (task 41): the account is disabled and, when an heir was named, its
 * creators were handed over. Codes are the enum names, so the notification module needs no enum
 * type from this module.
 *
 * @param tenantId company of the plan
 * @param userId the departing account
 * @param bdName display name of that account
 * @param typeCode {@code RESIGN} or {@code DISABLE}
 * @param effectiveAt effective time of the plan (UTC)
 * @param heirName display name of the heir, or null when none was named
 * @param resultCode {@code TRANSFERRED}, {@code UNASSIGNED} or {@code HEIR_INVALID}
 */
public record DeparturePlanExecutedEvent(
    long tenantId,
    long userId,
    String bdName,
    String typeCode,
    Instant effectiveAt,
    String heirName,
    String resultCode) {}
