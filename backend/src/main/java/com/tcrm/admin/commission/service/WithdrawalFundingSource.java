package com.tcrm.admin.commission.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  /**
   * Row-level allocatable money per creator (L-033): the same rows the FIFO allocation funds from,
   * each counted only up to its unoccupied leftover, so released money of after-sales lines stays
   * out. Creators without a row are absent from the map — read as zero.
   */
  Map<Long, BigDecimal> allocatableByKol(Collection<Long> kolIds);

  /**
   * The one 可提现 figure (design §4.8.2, L-033): the wallet money still free minus this creator's
   * active occupations, capped by the row-level allocatable amount. The conditions endpoint, the
   * application check and the overview pages all read it from here, so what the pages show always
   * equals the amount an application can actually allocate.
   */
  default BigDecimal availableCny(long kolId, BigDecimal balanceCny, BigDecimal occupiedCny) {
    BigDecimal rowLevel =
        allocatableByKol(List.of(kolId)).getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
    return balanceCny.subtract(occupiedCny).min(rowLevel);
  }

  /** Batch form of {@link #availableCny} for list pages; absent creators read as zero. */
  default Map<Long, BigDecimal> availableByKol(
      Collection<Long> kolIds, Map<Long, BigDecimal> balances, Map<Long, BigDecimal> occupied) {
    Map<Long, BigDecimal> allocatable = allocatableByKol(kolIds);
    Map<Long, BigDecimal> available = new HashMap<>();
    for (Long kolId : kolIds) {
      BigDecimal balance = balances.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
      BigDecimal held = occupied.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
      BigDecimal rowLevel = allocatable.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
      available.put(kolId, balance.subtract(held).min(rowLevel));
    }
    return available;
  }

  /**
   * Released wallet money the allocatable rows cannot back — blocked (after-sales or over-occupied)
   * lines that are neither available nor occupied. It stays displayed as frozen so the five-layer
   * equation 预估 = 冻结 + 可提现 + 占用 + 已付 keeps holding once 可提现 shrinks to the allocatable cap (L-033,
   * design §4.8.1「阻断期间该行一律显示冻结」).
   */
  default BigDecimal frozenShortfallCny(long kolId, BigDecimal balanceCny, BigDecimal occupiedCny) {
    BigDecimal wallet = balanceCny.subtract(occupiedCny);
    BigDecimal rowLevel =
        allocatableByKol(List.of(kolId)).getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
    return wallet.subtract(rowLevel).max(BigDecimal.ZERO.setScale(2));
  }

  /** Batch form of {@link #frozenShortfallCny} for list pages. */
  default Map<Long, BigDecimal> frozenShortfallByKol(
      Collection<Long> kolIds, Map<Long, BigDecimal> balances, Map<Long, BigDecimal> occupied) {
    Map<Long, BigDecimal> allocatable = allocatableByKol(kolIds);
    Map<Long, BigDecimal> shortfall = new HashMap<>();
    for (Long kolId : kolIds) {
      BigDecimal wallet =
          balances
              .getOrDefault(kolId, BigDecimal.ZERO.setScale(2))
              .subtract(occupied.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)));
      shortfall.put(
          kolId,
          wallet
              .subtract(allocatable.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)))
              .max(BigDecimal.ZERO.setScale(2)));
    }
    return shortfall;
  }

  /** Commission accrued but not released yet (frozen money, shown by the withdrawal conditions). */
  BigDecimal frozenCny(long kolId);

  /** Estimated total commission of verified rows (shown by the withdrawal conditions). */
  BigDecimal estimatedCny(long kolId);

  /** Order item rows not verified yet (shown by the withdrawal conditions). */
  long unverifiedCount(long kolId);

  /** Current settlement config version (0 before task 62's rule versions exist). */
  int configVersion(long kolId);

  /** One released order item with its leftover. */
  record FundingRow(long orderItemId, long itemStateId, BigDecimal releasedCny) {}
}
