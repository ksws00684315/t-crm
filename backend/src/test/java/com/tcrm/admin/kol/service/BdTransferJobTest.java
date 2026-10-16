package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BdTransferJobTest {
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final BdTransferService transfers = mock(BdTransferService.class);
  private final BdTransferJob job = new BdTransferJob(locks, tenants, transfers);

  @Test
  void shouldSkip_whenAnotherInstanceHoldsLock() throws Exception {
    job.run();
    verifyNoInteractions(tenants, transfers);
  }

  @Test
  void shouldRunForEachCompanyAndRestoreScope_whenLockAcquired() throws Exception {
    List<Long> scopes = new ArrayList<>();
    when(locks.run(eq("kol:bd-transfer"), eq(0L), eq(-1L), any()))
        .thenAnswer(
            i -> {
              i.getArgument(3, Runnable.class).run();
              return true;
            });
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    org.mockito.Mockito.doAnswer(
            i -> {
              scopes.add(TenantScope.companyId());
              return null;
            })
        .when(transfers)
        .executeDueBatch();
    job.run();
    assertThat(scopes).containsExactly(1L, 2L);
    assertThat(TenantScope.companyId()).isNull();
  }
}
