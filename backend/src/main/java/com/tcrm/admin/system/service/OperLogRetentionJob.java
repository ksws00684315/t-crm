package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
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
 * Deletes operation logs older than the current configured retention period (task 06b §4.4). The
 * table is append-only; this job is the only delete.
 */
@Component
public class OperLogRetentionJob {
  static final int BATCH_SIZE = 10_000;
  static final String LOCK = "system:oper-log-retention";
  private static final Logger LOG = LoggerFactory.getLogger(OperLogRetentionJob.class);

  private final SysOperLogMapper logs;
  private final TransactionTemplate tx;
  private final DistributedLocks locks;
  private final SystemConfigApi configs;

  public OperLogRetentionJob(
      SysOperLogMapper logs,
      PlatformTransactionManager transactions,
      DistributedLocks locks,
      SystemConfigApi configs) {
    this.logs = logs;
    this.locks = locks;
    this.configs = configs;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Every day at 03:45 UTC, after the 03:30 file-retention job. */
  @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(LOCK, 0, -1, () -> cleanup(Instant.now()));
  }

  /**
   * Deletes logs whose oper_time is strictly before now minus retention. Package-visible for tests.
   */
  void cleanup(Instant now) {
    int retentionDays = configs.getInt("oper-log.retention-days");
    Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
    long total = 0;
    int deleted;
    do {
      // System scope: the inner select of oldest ids must not be limited to one company.
      deleted =
          TenantScope.callSystem(
              "delete operation logs across companies",
              () -> tx.execute(status -> logs.deleteOlderThan(cutoff, BATCH_SIZE)));
      total += deleted;
    } while (deleted >= BATCH_SIZE);
    LOG.info(
        "Deleted {} operation logs older than {} days (before {})", total, retentionDays, cutoff);
  }
}
