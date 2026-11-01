package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund pause linkage (ADR-032 §6 退款阻断联动, design §4.7/§4.8.6): while an occupied item line blocks
 * — an unconcluded refund, or occupations (including PAID) that outrun its released money — the
 * active applications holding it cannot be paid; when every line of an application is unblocked
 * again, the pause lifts and a RESUMED history row is written. A pause that survives the refund
 * (the money no longer covers the occupation) stays until the application is rejected or withdrawn
 * — the creator reapplies against the updated balance (owner decision 2026-11-01). The snapshot
 * revision transaction calls in on every block-state transition, so order sync, refund sync, refund
 * import and the settlement recompute share one write path. Applications are locked one by one in
 * ascending id order — the same single-row FOR UPDATE the payment registration takes — and {@link
 * #prelockItem} runs before any wallet write of the revision, so the two transactions cannot
 * interleave and cannot deadlock.
 */
@Service
public class WithdrawalPauseService {
  private static final Logger LOG = LoggerFactory.getLogger(WithdrawalPauseService.class);

  /**
   * Machine reason stored with the pause; the pages show their own copy ({@code
   * finance.refundPause}), the value is evidence for the API consumer.
   */
  static final String PAUSE_REASON =
      "Blocked: an occupied order line is in an unconcluded refund (after-sales)";

  private final WithdrawalMapper withdrawals;
  private final Clock clock;

  @Autowired
  public WithdrawalPauseService(WithdrawalMapper withdrawals) {
    this(withdrawals, Clock.systemUTC());
  }

  WithdrawalPauseService(WithdrawalMapper withdrawals, Clock clock) {
    this.withdrawals = withdrawals;
    this.clock = clock;
  }

  /** Pauses every not-yet-paused active application holding this item state. */
  @Transactional(rollbackFor = Exception.class)
  public void pauseForItem(long itemStateId) {
    Instant now = clock.instant();
    for (long id : sorted(withdrawals.findActiveIdsByItemState(itemStateId, false))) {
      if (withdrawals.lockById(id) == null) {
        continue;
      }
      if (withdrawals.pause(id, PAUSE_REASON, now) == 1) {
        withdrawals.insertHistory(
            new WithdrawalWrite.History(
                IdWorker.getId(), id, "PAUSED", null, null, "SYSTEM", PAUSE_REASON, now));
        LOG.info("withdrawal {} paused by blocked item state {}", id, itemStateId);
      }
    }
  }

  /**
   * Lifts the pauses of the applications holding this item state once none of their allocated lines
   * blocks any more; one still-blocked line keeps the whole application paused.
   */
  @Transactional(rollbackFor = Exception.class)
  public void resumeForItem(long itemStateId) {
    Instant now = clock.instant();
    for (long id : sorted(withdrawals.findActiveIdsByItemState(itemStateId, true))) {
      if (withdrawals.lockById(id) == null) {
        continue;
      }
      if (withdrawals.countBlockedItems(id) > 0) {
        continue;
      }
      if (withdrawals.resume(id, now) == 1) {
        withdrawals.insertHistory(
            new WithdrawalWrite.History(
                IdWorker.getId(), id, "RESUMED", null, null, "SYSTEM", null, now));
        LOG.info("withdrawal {} resumed from unblocked item state {}", id, itemStateId);
      }
    }
  }

  /**
   * Locks — in ascending id order — the active applications holding this item state, on both the
   * paused and the not-yet-paused side. The snapshot revision transaction calls this BEFORE any
   * wallet write, so it never holds a wallet lock while waiting for an application lock: the
   * payment registration takes application → wallet, and the linkage must not take wallet →
   * application (deadlock).
   */
  @Transactional(rollbackFor = Exception.class)
  public void prelockItem(long itemStateId) {
    for (long id : sorted(withdrawals.findActiveIdsByItemState(itemStateId, false))) {
      withdrawals.lockById(id);
    }
    for (long id : sorted(withdrawals.findActiveIdsByItemState(itemStateId, true))) {
      withdrawals.lockById(id);
    }
  }

  /** Occupations (including PAID) holding one item state, for the before/after block check. */
  public BigDecimal occupiedOfItem(long itemStateId) {
    BigDecimal occupied = withdrawals.sumOccupiedByItemState(itemStateId);
    return occupied == null ? BigDecimal.ZERO.setScale(2) : occupied;
  }

  /** The blocked-line predicate (after-sales or over-occupied) for one item state. */
  public boolean isItemBlocked(long itemStateId) {
    return Boolean.TRUE.equals(withdrawals.isItemBlocked(itemStateId));
  }

  /** Lock discipline: ascending withdrawal id, whatever order the candidate query answered. */
  private static List<Long> sorted(List<Long> ids) {
    return ids == null ? List.of() : ids.stream().sorted().toList();
  }
}
