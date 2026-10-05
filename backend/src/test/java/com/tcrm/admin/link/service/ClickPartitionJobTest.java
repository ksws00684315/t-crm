package com.tcrm.admin.link.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.shared.DistributedLocks;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickPartitionJobTest {
  private final ClickLogMapper mapper = mock(ClickLogMapper.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final ClickPartitionJob job =
      new ClickPartitionJob(
          mapper, locks, Clock.fixed(Instant.parse("2026-12-15T00:10:00Z"), ZoneOffset.UTC));

  @Test
  void shouldCreateMissingAndDropExpiredPartitions() {
    when(mapper.listPartitions())
        .thenReturn(
            List.of("crm_click_log_202510", "crm_click_log_202512", "crm_click_log_202612"));

    job.maintain(LocalDate.of(2026, 12, 15));

    verify(mapper, never()).createPartition(eq("crm_click_log_202612"), anyString(), anyString());
    verify(mapper).createPartition("crm_click_log_202701", "2027-01-01", "2027-02-01");
    verify(mapper).commentPartition("crm_click_log_202701", "2027-01");
    verify(mapper).createPartition("crm_click_log_202702", "2027-02-01", "2027-03-01");
    var order = inOrder(mapper);
    order.verify(mapper).detachPartition("crm_click_log_202510");
    order.verify(mapper).dropPartition("crm_click_log_202510");
    verify(mapper, never()).detachPartition("crm_click_log_202512");
  }

  @Test
  void shouldDoNothing_whenAlreadyMaintained() {
    when(mapper.listPartitions())
        .thenReturn(
            List.of("crm_click_log_202612", "crm_click_log_202701", "crm_click_log_202702"));

    job.maintain(LocalDate.of(2026, 12, 15));

    verify(mapper, never()).createPartition(anyString(), anyString(), anyString());
    verify(mapper, never()).detachPartition(anyString());
  }

  @Test
  void shouldRunUnderLock_withUtcDate() throws Exception {
    when(locks.run(eq(ClickPartitionJob.LOCK), eq(0L), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(3).run();
              return true;
            });
    when(mapper.listPartitions()).thenReturn(List.of());

    job.scheduled();

    verify(mapper).createPartition("crm_click_log_202612", "2026-12-01", "2027-01-01");
  }

  @Test
  void shouldSkip_whenLockHeldElsewhere() throws Exception {
    when(locks.run(anyString(), anyLong(), anyLong(), any())).thenReturn(false);
    job.scheduled();
    verify(mapper, never()).listPartitions();
  }
}
