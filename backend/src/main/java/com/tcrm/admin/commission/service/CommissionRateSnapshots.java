package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Latest rate revision per commission item state, shared by the chain page and the withdrawal
 * detail (task 115 review: one lookup, no duplicated revision loops).
 */
final class CommissionRateSnapshots {

  private CommissionRateSnapshots() {}

  /**
   * Latest revision rate keyed by item-state id; states without a revision are absent. Callers run
   * inside the company tenant scope.
   */
  static Map<Long, BigDecimal> rateByStateId(
      CommissionItemRevisionMapper revisions, Collection<Long> stateIds) {
    if (stateIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> rateByState = new HashMap<>();
    for (CommissionItemRevisionDO revision : revisions.listLatestByItemStateIds(stateIds)) {
      rateByState.put(revision.getItemStateId(), revision.getRate());
    }
    return rateByState;
  }
}
