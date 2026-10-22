package com.tcrm.admin.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OrderSyncFailureRetentionJobTest {
  private static final Instant NOW = Instant.parse("2026-10-22T04:15:00Z");

  private final OrderSyncFailureMapper failures = mock(OrderSyncFailureMapper.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final OrderSyncFailureRetentionJob job =
      new OrderSyncFailureRetentionJob(
          failures, mock(PlatformTransactionManager.class), locks, configs);

  @Test
  void shouldSkip_whenRetentionIsZero() {
    when(configs.getInt("order.sync-failure.retention-days")).thenReturn(0);
    job.cleanup(NOW);
    verify(failures, never()).deleteOlderThan(any(), anyInt());
  }

  @Test
  void shouldDeleteOlderRows_whenRetentionPositive() {
    when(configs.getInt("order.sync-failure.retention-days")).thenReturn(30);
    when(failures.deleteOlderThan(any(), anyInt())).thenReturn(0);
    job.cleanup(NOW);
    verify(failures)
        .deleteOlderThan(NOW.minus(30, ChronoUnit.DAYS), OrderSyncFailureRetentionJob.BATCH_SIZE);
  }
}
