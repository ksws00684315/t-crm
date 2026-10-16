package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FxRateFetchJobTest {
  private final FxRateService rates = mock(FxRateService.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final FxRateFetchJob job = new FxRateFetchJob(rates, locks);

  @Test
  void shouldUpdateInSystemScope_whenHoldingTheLockWithoutWaiting() throws InterruptedException {
    AtomicBoolean inSystem = new AtomicBoolean();
    doAnswer(
            invocation -> {
              inSystem.set(TenantScope.inSystem());
              return null;
            })
        .when(rates)
        .update(any(Instant.class));
    when(locks.run(eq(FxRateFetchJob.LOCK), eq(0L), eq(-1L), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(3)).run();
              return true;
            });

    job.run();

    verify(rates).update(any(Instant.class));
    assertThat(FxRateFetchJob.LOCK).isEqualTo("fx:fetch");
    assertThat(inSystem).isTrue();
    assertThat(TenantScope.inSystem()).isFalse();
  }
}
