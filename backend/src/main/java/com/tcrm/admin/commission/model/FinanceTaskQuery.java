package com.tcrm.admin.commission.model;

import java.util.List;

/**
 * Query for the unified finance task list (13.03). The service merges the withdrawal and prepay
 * branches, so each branch reads at most {@code limit} rows and the filters repeat per branch:
 * {@code applicationNo} and {@code kolId} hit the branch's own number column, and the shared
 * lifecycle {@code status} maps to that branch's raw statuses.
 */
public record FinanceTaskQuery(
    String applicationNo,
    Long kolId,
    String status,
    String taskType,
    List<Long> kolIds,
    List<String> withdrawalStatuses,
    List<String> prepayStatuses,
    int limit) {}
