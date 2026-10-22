package com.tcrm.admin.order.service;

import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optional daily cleanup of order sync failures. Retention days default to 0 (off); when greater
 * than 0, deletes rows older than the cutoff across companies (task 40).
 */
@Component
public class OrderSyncFailureRetentionJob {
  static final int BATCH_SIZE = 10_000;
  static final String LOCK = "order:sync-failure-retention";
  private static final Logger LOG = LoggerFactory.getLogger(OrderSyncFailureRetentionJob.class);

  private final OrderSyncFailureMapper failures;
  private final TransactionTemplate tx;
  private final DistributedLocks locks;
  private final SystemConfigApi configs;

  public OrderSyncFailureRetentionJob(
      OrderSyncFailureMapper failures,
      PlatformTransactionManager transactions,
      DistributedLocks locks,
      SystemConfigApi configs) {
    this.failures = failures;
    this.locks = locks;
    this.configs = configs;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Every day at 04:15 UTC, after oper-log retention at 03:45. */
  @Scheduled(cron = "0 15 4 * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(LOCK, 0, -1, () -> cleanup(Instant.now()));
  }

  /** Package-visible for tests. */
  void cleanup(Instant now) {
    int retentionDays = configs.getInt("order.sync-failure.retention-days");
    if (retentionDays <= 0) {
      return;
    }
    Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
    long total = 0;
    int deleted;
    do {
      deleted =
          TenantScope.callSystem(
              "delete order sync failures across companies",
              () -> tx.execute(status -> failures.deleteOlderThan(cutoff, BATCH_SIZE)));
      total += deleted;
    } while (deleted >= BATCH_SIZE);
    LOG.info(
        "Deleted {} order sync failures older than {} days (before {})",
        total,
        retentionDays,
        cutoff);
  }
}
