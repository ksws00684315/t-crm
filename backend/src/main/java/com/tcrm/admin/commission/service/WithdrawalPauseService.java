package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund pause linkage (ADR-032 §6 退款阻断联动, design §4.7/§4.8.6): while an occupied item line sits in
 * an unconcluded refund, the active applications holding it cannot be paid; when every line of an
 * application is unblocked again, the pause lifts and a RESUMED history row is written. The
 * snapshot revision transaction calls in on every after-sales transition, so order sync, refund
 * sync, refund import and the settlement recompute share one write path. Applications are locked
 * one by one in ascending id order — the same single-row FOR UPDATE the payment registration takes
 * — so the two transactions cannot interleave and cannot deadlock.
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
        LOG.info("withdrawal {} paused by after-sales item state {}", id, itemStateId);
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
        LOG.info("withdrawal {} resumed from after-sales item state {}", id, itemStateId);
      }
    }
  }

  /** Lock discipline: ascending withdrawal id, whatever order the candidate query answered. */
  private static List<Long> sorted(List<Long> ids) {
    return ids == null ? List.of() : ids.stream().sorted().toList();
  }
}
