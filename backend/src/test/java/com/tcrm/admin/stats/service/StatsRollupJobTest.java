package com.tcrm.admin.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StatsRollupJobTest {
  @Test
  void serializesPerTenantAndRunsAllThreeRebuildWindows() throws InterruptedException {
    var tenants = mock(EnabledTenantApi.class);
    var locks = mock(DistributedLocks.class);
    var rollups = mock(StatsRollupService.class);
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    when(locks.run(anyString(), eq(0L), eq(-1L), any()))
        .thenAnswer(
            call -> {
              String name = call.getArgument(0);
              if (name.endsWith(":2")) {
                return false;
              }
              Runnable work = call.getArgument(3);
              work.run();
              return true;
            });
    when(rollups.rebuildNext(eq(1L), any()))
        .thenAnswer(
            call -> {
              assertThat(TenantScope.companyId()).isEqualTo(1);
              return false;
            });
    var job = new StatsRollupJob(tenants, locks, rollups);
    job.incremental();
    verify(rollups).scan(eq(1L), any());
    job.previousDay();
    job.previousMonth();
    var start = ArgumentCaptor.forClass(LocalDate.class);
    var end = ArgumentCaptor.forClass(LocalDate.class);
    var time = ArgumentCaptor.forClass(Instant.class);
    verify(rollups, org.mockito.Mockito.times(2))
        .enqueue(eq(1L), start.capture(), end.capture(), time.capture());
    verify(rollups, org.mockito.Mockito.times(2)).enqueue(eq(2L), any(), any(), any());
    LocalDate today = time.getAllValues().getFirst().atZone(StatsPeriod.ZONE).toLocalDate();
    assertThat(start.getAllValues().getFirst()).isEqualTo(today.minusDays(1));
    assertThat(end.getAllValues().getFirst()).isEqualTo(today);
    assertThat(start.getAllValues().getLast())
        .isEqualTo(end.getAllValues().getLast().minusMonths(1));
    assertThat(end.getAllValues().getLast().getDayOfMonth()).isEqualTo(1);
    assertThat(TenantScope.companyId()).isNull();
  }

  @Test
  void isolatesTenantFailures() throws InterruptedException {
    var tenants = mock(EnabledTenantApi.class);
    var locks = mock(DistributedLocks.class);
    var rollups = mock(StatsRollupService.class);
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    when(locks.run(anyString(), anyLong(), anyLong(), any()))
        .thenAnswer(
            call -> {
              Runnable work = call.getArgument(3);
              work.run();
              return true;
            });
    doThrow(IllegalStateException.class).when(rollups).scan(eq(1L), any());
    new StatsRollupJob(tenants, locks, rollups).incremental();
    verify(rollups).scan(eq(2L), any());
    assertThat(TenantScope.companyId()).isNull();
  }
}
