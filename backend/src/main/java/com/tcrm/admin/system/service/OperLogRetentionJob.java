package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.DistributedLocks;
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
 * Deletes operation logs older than {@link #RETENTION_DAYS} days (task 06b §4.4). The table is
 * append-only; this job is the only delete.
 */
@Component
public class OperLogRetentionJob {
  /** Retention for sys_oper_log; not configurable (engineering spec 06, section 4). */
  public static final int RETENTION_DAYS = 180;

  static final int BATCH_SIZE = 10_000;
  static final String LOCK = "system:oper-log-retention";
  private static final Logger LOG = LoggerFactory.getLogger(OperLogRetentionJob.class);

  private final SysOperLogMapper logs;
  private final TransactionTemplate tx;
  private final DistributedLocks locks;

  public OperLogRetentionJob(
      SysOperLogMapper logs, PlatformTransactionManager transactions, DistributedLocks locks) {
    this.logs = logs;
    this.locks = locks;
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
    Instant cutoff = now.minus(RETENTION_DAYS, ChronoUnit.DAYS);
    long total = 0;
    int deleted;
    do {
      deleted = tx.execute(status -> logs.deleteOlderThan(cutoff, BATCH_SIZE));
      total += deleted;
    } while (deleted >= BATCH_SIZE);
    LOG.info(
        "Deleted {} operation logs older than {} days (before {})", total, RETENTION_DAYS, cutoff);
  }
}
