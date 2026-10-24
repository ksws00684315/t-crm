package com.tcrm.admin.commission.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Released commission per order item — the settlement side of the withdrawal contract (task 61
 * §13.04: 占用金额 + 剩余快照). Task 62's settlement engine implements this against its item state; until
 * that lands, {@link UnsettledWithdrawalFundingSource} keeps the bean available and tests stub it
 * with fixed rows.
 */
public interface WithdrawalFundingSource {
  /**
   * Order items of this creator whose commission has been released to the wallet, in stable FIFO
   * order (release time, then order item id). Each row carries the item's total released amount;
   * the withdrawal side subtracts its own allocations.
   */
  List<FundingRow> listReleasedRows(long kolId);

  /** Commission accrued but not released yet (frozen money, shown by the withdrawal conditions). */
  BigDecimal frozenCny(long kolId);

  /** One released order item. */
  record FundingRow(long orderItemId, BigDecimal releasedCny) {}
}
