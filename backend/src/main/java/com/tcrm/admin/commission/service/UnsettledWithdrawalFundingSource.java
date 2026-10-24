package com.tcrm.admin.commission.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Placeholder funding source until the settlement engine (task 62) merges: no order item is
 * verifiably released, so every withdrawal application is rejected with insufficient funds. Task 62
 * replaces this bean with the real implementation backed by commission item state.
 */
@Component
public class UnsettledWithdrawalFundingSource implements WithdrawalFundingSource {
  @Override
  public List<FundingRow> listReleasedRows(long kolId) {
    return List.of();
  }

  @Override
  public BigDecimal frozenCny(long kolId) {
    return BigDecimal.ZERO.setScale(2);
  }
}
