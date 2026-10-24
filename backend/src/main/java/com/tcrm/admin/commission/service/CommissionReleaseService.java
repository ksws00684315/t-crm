package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.shared.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release of due commission accruals into the wallet (task 62, design §4.8.2 / §9, ADR-032 §5).
 * Rows release when {@code release_at <= now} and accrual exceeds what was already released; the
 * credit goes through {@code postCommissionCredit} so the FIFO prepay recovery applies. Runs for
 * one company inside the caller's tenant scope; the job iterates enabled companies.
 */
@Service
public class CommissionReleaseService {
  private static final Logger LOG = LoggerFactory.getLogger(CommissionReleaseService.class);
  static final int BATCH_LIMIT = 500;

  private final CommissionItemStateMapper states;
  private final CommissionWalletService wallet;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public CommissionReleaseService(
      CommissionItemStateMapper states, CommissionWalletService wallet, TransactionTemplate tx) {
    this(states, wallet, tx, Clock.systemUTC());
  }

  CommissionReleaseService(
      CommissionItemStateMapper states,
      CommissionWalletService wallet,
      TransactionTemplate tx,
      Clock clock) {
    this.states = states;
    this.wallet = wallet;
    this.tx = tx;
    this.clock = clock;
  }

  /** Releases every due row of the current company; returns the number of rows released. */
  public int releaseDue() {
    Instant now = clock.instant();
    int released = 0;
    List<CommissionItemStateDO> due;
    while (!(due = states.listDueForRelease(now, BATCH_LIMIT)).isEmpty()) {
      int releasedThisBatch = 0;
      for (CommissionItemStateDO row : due) {
        Boolean ok =
            tx.execute(
                status -> {
                  try {
                    return releaseOne(row, now);
                  } catch (RuntimeException e) {
                    LOG.error("commission release failed for item state {}", row.getId(), e);
                    return false;
                  }
                });
        if (Boolean.TRUE.equals(ok)) {
          released++;
          releasedThisBatch++;
        }
      }
      // A batch without progress means the remaining rows keep failing (or already conflict);
      // stop instead of spinning on the same rows until the next scheduled run.
      if (releasedThisBatch == 0) {
        break;
      }
    }
    return released;
  }

  /**
   * Releases one row in the caller's transaction; the row is re-locked and re-checked because the
   * batch read is not locked.
   */
  public boolean releaseOne(CommissionItemStateDO candidate, Instant now) {
    CommissionItemStateDO row = states.lockById(candidate.getId());
    if (row == null
        || !Boolean.TRUE.equals(row.getVerified())
        || row.getReleaseAt() == null
        || row.getReleaseAt().isAfter(now)
        || row.getKolId() == null) {
      return false;
    }
    BigDecimal accrued = row.getAccruedCommissionCny();
    BigDecimal released = row.getReleasedCommissionCny();
    BigDecimal credit = accrued.subtract(released);
    if (credit.signum() <= 0) {
      return false;
    }
    Long tenantId = row.getTenantId();
    if (tenantId == null) {
      LOG.error("commission item state {} has no tenant; skipping release", row.getId());
      return false;
    }
    try {
      wallet.postCommissionCredit(
          new CommissionWalletService.CreditCommand(
              tenantId,
              row.getKolId(),
              row.getId(),
              CommissionItemSnapshotService.releaseSourceKey(row.getId(), row.getSourceVersion()),
              credit,
              row.getReleaseAt()));
    } catch (BusinessException e) {
      if (e.getErrorCode() == com.tcrm.admin.shared.ErrorCode.PREPAY_REQUEST_CONFLICT) {
        // Same source key already posted with a different amount: the ledger is authoritative,
        // align the row instead of retrying forever (the wallet check job would flag a real gap).
        LOG.warn("release source key conflict for item state {}: {}", row.getId(), e.getMessage());
        states.updateReleased(row.getId(), released, accrued, now);
        return false;
      }
      throw e;
    }
    if (states.updateReleased(row.getId(), released, accrued, now) != 1) {
      // The locked row changed underneath; count this batch row as unreleased so the loop's
      // no-progress guard stops the run instead of spinning on the same row.
      LOG.warn("release bookkeeping lost the row for item state {}", row.getId());
      return false;
    }
    LOG.info(
        "commission released: itemState={} kol={} credit={}", row.getId(), row.getKolId(), credit);
    return true;
  }
}
