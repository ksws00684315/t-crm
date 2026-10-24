package com.tcrm.admin.commission.model;

import java.util.List;

/**
 * Withdrawal page query (contract §6.2): {@code kolId} exact, {@code kolIds} narrows the
 * keyword/BD/OWN filters, and {@code ownIds} keeps an OWN-scope viewer on their own creators.
 */
public record WithdrawalPageQuery(
    String applicationNo,
    Long kolId,
    List<Long> kolIds,
    List<Long> bdIds,
    String status,
    List<Long> ownIds,
    int limit,
    long offset) {}
