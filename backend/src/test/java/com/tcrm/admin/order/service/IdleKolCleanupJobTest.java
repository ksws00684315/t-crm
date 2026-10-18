package com.tcrm.admin.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdleKolCleanupJobTest {
  @Test
  void shouldRunCleanup_forEachEnabledTenant() throws Exception {
    DistributedLocks locks = mock(DistributedLocks.class);
    EnabledTenantApi tenants = mock(EnabledTenantApi.class);
    IdleKolCleanupService cleanup = mock(IdleKolCleanupService.class);
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 2L));
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(3)).run();
              return true;
            })
        .when(locks)
        .run(anyString(), anyLong(), anyLong(), any());

    new IdleKolCleanupJob(locks, tenants, cleanup).run();

    verify(locks).run(eq("kol:idle-cleanup"), eq(0L), eq(-1L), any());
    verify(cleanup, org.mockito.Mockito.times(2)).executeForCurrentTenant();
  }
}
