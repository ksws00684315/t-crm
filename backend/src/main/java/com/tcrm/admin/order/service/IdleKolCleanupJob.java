package com.tcrm.admin.order.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily 03:00 Asia/Shanghai idle-KOL cleanup (task 25a / ADR-023 §5; orchestration in order). */
@Component
public class IdleKolCleanupJob {
  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final IdleKolCleanupService cleanup;

  public IdleKolCleanupJob(
      DistributedLocks locks, EnabledTenantApi tenants, IdleKolCleanupService cleanup) {
    this.locks = locks;
    this.tenants = tenants;
    this.cleanup = cleanup;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
  public void run() throws InterruptedException {
    locks.run(
        "kol:idle-cleanup",
        0,
        -1,
        () -> {
          for (Long tenant : tenants.enabledTenantIds()) {
            TenantScope.runCompany(tenant, cleanup::executeForCurrentTenant);
          }
        });
  }
}
