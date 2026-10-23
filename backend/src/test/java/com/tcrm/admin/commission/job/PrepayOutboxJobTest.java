package com.tcrm.admin.commission.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.service.PrepayOutboxDelivery;
import com.tcrm.admin.shared.DistributedLocks;
import org.junit.jupiter.api.Test;

class PrepayOutboxJobTest {
  private final PrepayOutboxDelivery delivery = mock(PrepayOutboxDelivery.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final PrepayOutboxJob job = new PrepayOutboxJob(delivery, locks);

  @Test
  void shouldDeliverUnderLockWithoutWaiting() throws InterruptedException {
    when(locks.run(eq(PrepayOutboxJob.LOCK), eq(0L), eq(-1L), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(3)).run();
              return true;
            });

    job.deliverOne();

    verify(delivery).deliverOne();
    assertThat(PrepayOutboxJob.LOCK).isEqualTo("commission:prepay-outbox");
  }

  @Test
  void shouldSkipDelivery_whenLockIsHeld() throws InterruptedException {
    when(locks.run(eq(PrepayOutboxJob.LOCK), eq(0L), eq(-1L), any(Runnable.class)))
        .thenReturn(false);

    job.deliverOne();

    verify(delivery, never()).deliverOne();
  }
}
