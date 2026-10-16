package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute, one instance executes up to 200 due transfers per enabled company (design §9). */
@Component
public class BdTransferJob {
  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final BdTransferService transfers;

  public BdTransferJob(
      DistributedLocks locks, EnabledTenantApi tenants, BdTransferService transfers) {
    this.locks = locks;
    this.tenants = tenants;
    this.transfers = transfers;
  }

  @Scheduled(cron = "0 * * * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(
        "kol:bd-transfer",
        0,
        -1,
        () -> {
          for (Long tenant : tenants.enabledTenantIds()) {
            TenantScope.runCompany(tenant, transfers::executeDueBatch);
          }
        });
  }
}
