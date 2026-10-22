package com.tcrm.admin.system.api;

import java.time.Instant;

/**
 * A BD submitted a resignation about itself (task 41). The company's super administrators are
 * reminded to name the heir; the message is written by the notification module after commit.
 *
 * @param tenantId company of the plan
 * @param userId account that resigned
 * @param bdName display name of that account
 * @param plannedLeaveAt effective time of the plan (UTC)
 */
public record DeparturePlanSubmittedEvent(
    long tenantId, long userId, String bdName, Instant plannedLeaveAt) {}
