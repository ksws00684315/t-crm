package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the unified finance task list (13.03): commission withdrawals and creator prepays
 * share the page, each with its own raw status (contract §6.2: status 为两种流程的原状态值).
 */
public record FinanceTaskRow(
    long id,
    long kolId,
    String taskNo,
    String taskType,
    String status,
    BigDecimal amountCny,
    Instant createdAt) {}
