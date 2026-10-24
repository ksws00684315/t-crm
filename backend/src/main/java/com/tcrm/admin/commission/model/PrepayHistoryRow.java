package com.tcrm.admin.commission.model;

import java.time.Instant;

/** One appended prepay history row. */
public record PrepayHistoryRow(
    String action,
    long operatorId,
    String operatorName,
    String operatorRole,
    Instant createTime,
    String reason) {}
