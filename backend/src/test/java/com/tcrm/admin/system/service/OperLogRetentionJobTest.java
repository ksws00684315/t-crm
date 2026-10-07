package com.tcrm.admin.system.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OperLogRetentionJobTest {
  private static final Instant NOW = Instant.parse("2026-10-07T03:45:00Z");

  private final SysOperLogMapper logs = mock(SysOperLogMapper.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final OperLogRetentionJob job =
      new OperLogRetentionJob(logs, mock(PlatformTransactionManager.class), locks);

  @Test
  void shouldDeleteInBatches_untilABatchIsShorterThanTheLimit() {
    when(logs.deleteOlderThan(any(), eq(OperLogRetentionJob.BATCH_SIZE)))
        .thenReturn(10_000, 10_000, 4);

    job.cleanup(NOW);

    verify(logs, times(3))
        .deleteOlderThan(
            NOW.minus(OperLogRetentionJob.RETENTION_DAYS, ChronoUnit.DAYS),
            OperLogRetentionJob.BATCH_SIZE);
  }

  @Test
  void shouldStop_whenTheFirstBatchDeletesNothing() {
    when(logs.deleteOlderThan(any(), anyInt())).thenReturn(0);

    job.cleanup(NOW);

    verify(logs).deleteOlderThan(NOW.minus(180, ChronoUnit.DAYS), 10_000);
  }

  @Test
  void shouldCleanupUnderTheLock() throws InterruptedException {
    when(locks.run(eq(OperLogRetentionJob.LOCK), eq(0L), eq(-1L), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(3).run();
              return true;
            });
    when(logs.deleteOlderThan(any(), anyInt())).thenReturn(0);

    job.run();

    verify(logs).deleteOlderThan(any(), eq(OperLogRetentionJob.BATCH_SIZE));
  }
}
