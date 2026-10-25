package com.tcrm.admin.commission.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Settlement side of the withdrawal contract (ADR-032 §6), implemented by {@link
 * ItemStateWithdrawalFundingSource} over task 62's commission item state.
 */
public interface WithdrawalFundingSource {
  /**
   * Order items of this creator whose commission has been released to the wallet and is not yet
   * occupied, in stable FIFO order (release time, then order item id). Each row carries the item's
   * leftover released amount and its commission item state id.
   */
  List<FundingRow> listReleasedRows(long kolId);

  /** Commission accrued but not released yet (frozen money, shown by the withdrawal conditions). */
  BigDecimal frozenCny(long kolId);

  /** Estimated total commission of verified rows (shown by the withdrawal conditions). */
  BigDecimal estimatedCny(long kolId);

  /** Order item rows not verified yet (shown by the withdrawal conditions). */
  long unverifiedCount(long kolId);

  /** Current settlement config version ("0" before task 62's rule versions exist). */
  String configVersion(long kolId);

  /** One released order item with its leftover. */
  record FundingRow(long orderItemId, long itemStateId, BigDecimal releasedCny) {}
}
