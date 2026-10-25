package com.tcrm.admin.commission.model;

import java.util.List;

/**
 * Query for the unified finance task list (13.03, contract §6.2). Each branch reads at most {@code
 * limit} rows and keeps its own scope list: {@code withdrawalScope} narrows OWN viewers to their
 * own creators, {@code prepayScope} follows the prepay rules (null means company-wide). {@code
 * status} matches the raw status value of each workflow.
 */
public record FinanceTaskQuery(
    String applicationNo,
    Long kolId,
    String status,
    String taskType,
    List<Long> withdrawalScope,
    List<Long> prepayScope,
    int limit) {}
