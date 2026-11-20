package com.tcrm.admin.order.api;

import java.time.Instant;

/** Changed order facts and an optional former payment or refund timestamp. */
public record OrderStatsDateChangedEvent(long tenantId, long orderId, Instant formerTime) {}
