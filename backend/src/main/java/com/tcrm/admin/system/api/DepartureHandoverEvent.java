package com.tcrm.admin.system.api;

import java.time.Instant;

/**
 * Published before {@link StaffDisabledEvent} when a departure plan with an heir takes effect: the
 * heir inherits every creator of the departing BD (task 41). Subscribers run before commit and in
 * publication order, so the creators move before the disable would unassign them.
 *
 * @param tenantId company of both accounts
 * @param fromBdUserId the departing BD, positive
 * @param toBdUserId the heir, positive
 * @param effectiveAt agreed effective time of the plan (UTC)
 * @param reason written to the transfer records and the audit row
 * @param actorId account to credit in the audit rows, or null for the plan itself
 */
public record DepartureHandoverEvent(
    long tenantId,
    long fromBdUserId,
    Long toBdUserId,
    Instant effectiveAt,
    String reason,
    Long actorId) {}
