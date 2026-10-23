package com.tcrm.admin.commission.job;

import com.tcrm.admin.commission.service.PrepayOutboxDelivery;
import com.tcrm.admin.shared.DistributedLocks;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the prepay outbox. The transactional work lives on {@link PrepayOutboxDelivery}. */
@Component
public class PrepayOutboxJob {
  static final String LOCK = "commission:prepay-outbox";

  private final PrepayOutboxDelivery delivery;
  private final DistributedLocks locks;

  public PrepayOutboxJob(PrepayOutboxDelivery delivery, DistributedLocks locks) {
    this.delivery = delivery;
    this.locks = locks;
  }

  /** Idle poll. Skipped when another instance holds {@link #LOCK}. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void deliverOne() throws InterruptedException {
    locks.run(LOCK, 0, -1, delivery::deliverOne);
  }
}
