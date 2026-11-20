package com.tcrm.admin.stats.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Incremental, prior-day and prior-month rebuilds share a tenant lock and the same durable queue.
 */
@Component
public class StatsRollupJob {
  private static final Logger LOG = LoggerFactory.getLogger(StatsRollupJob.class);
  private static final int MAX_DAYS_PER_RUN = 31;
  private final EnabledTenantApi tenants;
  private final DistributedLocks locks;
  private final StatsRollupService rollups;

  public StatsRollupJob(
      EnabledTenantApi tenants, DistributedLocks locks, StatsRollupService rollups) {
    this.tenants = tenants;
    this.locks = locks;
    this.rollups = rollups;
  }

  @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Shanghai")
  public void incremental() throws InterruptedException {
    var now = Instant.now();
    forTenant(
        tenant ->
            withLock(
                tenant,
                () -> {
                  rollups.scan(tenant, now);
                  drain(tenant, now);
                }));
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
  public void previousDay() throws InterruptedException {
    var now = Instant.now();
    var today = now.atZone(StatsPeriod.ZONE).toLocalDate();
    recalculate(today.minusDays(1), today, now);
  }

  @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Shanghai")
  public void previousMonth() throws InterruptedException {
    var now = Instant.now();
    var month = now.atZone(StatsPeriod.ZONE).toLocalDate().withDayOfMonth(1);
    recalculate(month.minusMonths(1), month, now);
  }

  private void recalculate(LocalDate from, LocalDate until, Instant now)
      throws InterruptedException {
    forTenant(
        tenant -> {
          rollups.enqueue(tenant, from, until, now);
          withLock(tenant, () -> drain(tenant, now));
        });
  }

  private void drain(long tenant, Instant now) {
    for (int count = 0; count < MAX_DAYS_PER_RUN && rollups.rebuildNext(tenant, now); count++) {
      // Each day commits independently, so failures leave the unfinished dates queued.
    }
  }

  private void withLock(long tenant, Runnable action) throws InterruptedException {
    locks.run("stats:rollup:" + tenant, 0, -1, action);
  }

  private void forTenant(TenantWork work) throws InterruptedException {
    for (long tenant : tenants.enabledTenantIds()) {
      try (var ignored = TenantScope.openCompany(tenant)) {
        work.run(tenant);
      } catch (RuntimeException e) {
        LOG.error("Statistics rebuild failed for tenant {}", tenant, e);
      }
    }
  }

  private interface TenantWork {
    void run(long tenant) throws InterruptedException;
  }
}
