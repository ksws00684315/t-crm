package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, one instance executes up to 200 due departure plans per enabled company (task 41
 * §2.1), with the same lock pattern as the scheduled transfer job.
 */
@Component
public class DeparturePlanJob {
  private final DistributedLocks locks;
  private final EnabledTenantService tenants;
  private final DepartureService departures;

  public DeparturePlanJob(
      DistributedLocks locks, EnabledTenantService tenants, DepartureService departures) {
    this.locks = locks;
    this.tenants = tenants;
    this.departures = departures;
  }

  @Scheduled(cron = "0 * * * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(
        "system:departure-plan",
        0,
        -1,
        () -> {
          for (Long tenant : tenants.enabledTenantIds()) {
            TenantScope.runCompany(tenant, departures::executeDueBatch);
          }
        });
  }
}
