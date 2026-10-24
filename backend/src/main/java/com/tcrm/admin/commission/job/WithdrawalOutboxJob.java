package com.tcrm.admin.commission.job;

import com.tcrm.admin.commission.service.WithdrawalOutboxDelivery;
import com.tcrm.admin.shared.DistributedLocks;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the withdrawal outbox. The transactional work lives on {@link WithdrawalOutboxDelivery}.
 */
@Component
public class WithdrawalOutboxJob {
  static final String LOCK = "commission:withdrawal-outbox";

  private final WithdrawalOutboxDelivery delivery;
  private final DistributedLocks locks;

  public WithdrawalOutboxJob(WithdrawalOutboxDelivery delivery, DistributedLocks locks) {
    this.delivery = delivery;
    this.locks = locks;
  }

  /** Idle poll. Skipped when another instance holds {@link #LOCK}. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void deliverOne() throws InterruptedException {
    locks.run(LOCK, 0, -1, delivery::deliverOne);
  }
}
