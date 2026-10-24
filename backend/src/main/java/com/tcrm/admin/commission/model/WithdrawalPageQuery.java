package com.tcrm.admin.commission.model;

import java.util.List;

/** Withdrawal page query; {@code kolIds} narrows an OWN-scope viewer to their own creators. */
public record WithdrawalPageQuery(
    String applicationNo, Long kolId, String status, List<Long> kolIds, int limit, long offset) {}
