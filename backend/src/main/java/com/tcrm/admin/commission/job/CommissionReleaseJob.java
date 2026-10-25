package com.tcrm.admin.commission.job;

import com.tcrm.admin.commission.service.CommissionReleaseService;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Commission release job (design §9): every 5 minutes, one holder of the {@code commission:release}
 * lock iterates enabled companies and releases due accruals into the wallet (COMMISSION_CREDIT with
 * FIFO prepay recovery, ADR-032 §5).
 */
@Component
public class CommissionReleaseJob {
  private static final Logger LOG = LoggerFactory.getLogger(CommissionReleaseJob.class);
  static final String LOCK = "commission:release";

  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final CommissionReleaseService releases;

  public CommissionReleaseJob(
      DistributedLocks locks, EnabledTenantApi tenants, CommissionReleaseService releases) {
    this.locks = locks;
    this.tenants = tenants;
    this.releases = releases;
  }

  @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
  public void releaseDue() throws InterruptedException {
    locks.run(
        LOCK,
        0,
        -1,
        () -> {
          int total = 0;
          for (Long tenant : tenants.enabledTenantIds()) {
            total += TenantScope.callCompany(tenant, () -> releases.releaseDue());
          }
          if (total > 0) {
            LOG.info("commission release run finished: {} rows", total);
          }
        });
  }
}
