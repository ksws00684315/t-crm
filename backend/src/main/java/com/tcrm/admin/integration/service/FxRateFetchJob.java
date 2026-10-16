package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fetches the ECB reference rate every hour (design §7.5, §9). The feed is tiny and a date already
 * stored is skipped, so hourly runs pick up the daily publication (about 16:00 CET) and retry
 * failures without separate retry logic.
 */
@Component
public class FxRateFetchJob {
  static final String LOCK = "fx:fetch";

  private final FxRateService rates;
  private final DistributedLocks locks;

  public FxRateFetchJob(FxRateService rates, DistributedLocks locks) {
    this.rates = rates;
    this.locks = locks;
  }

  /** Every hour at minute 30 UTC; skipped when another instance holds the lock. */
  @Scheduled(cron = "0 30 * * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(LOCK, 0, -1, () -> fetch(Instant.now()));
  }

  /** Package-visible for tests. */
  void fetch(Instant now) {
    // System scope (design §3.4 item 4): crm_fx_rate is platform-level data shared by every
    // company, not one company's rows; the job reads and writes nothing else.
    TenantScope.runSystem("fetch the platform-level ECB reference rate", () -> rates.update(now));
  }
}
