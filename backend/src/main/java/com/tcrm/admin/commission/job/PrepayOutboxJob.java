package com.tcrm.admin.commission.job;

import com.tcrm.admin.commission.service.PrepayOutboxDelivery;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the prepay outbox. The transactional work lives on {@link PrepayOutboxDelivery}. */
@Component
public class PrepayOutboxJob {
  private final PrepayOutboxDelivery delivery;

  public PrepayOutboxJob(PrepayOutboxDelivery delivery) {
    this.delivery = delivery;
  }

  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void deliverOne() {
    delivery.deliverOne();
  }
}
