package com.tcrm.admin.link.service;

import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily, idempotent partition maintenance of {@code crm_click_log} (task 06 §4.5, design §9):
 * creates missing partitions for the current and next two months and drops expired ones (DETACH,
 * then DROP).
 */
@Service
public class ClickPartitionJob {
  static final String LOCK = "link:click-partitions";
  private static final Logger LOG = LoggerFactory.getLogger(ClickPartitionJob.class);
  private final ClickLogMapper clickLogs;
  private final DistributedLocks locks;
  private final Clock clock;

  @Autowired
  public ClickPartitionJob(ClickLogMapper clickLogs, DistributedLocks locks) {
    this(clickLogs, locks, Clock.systemUTC());
  }

  ClickPartitionJob(ClickLogMapper clickLogs, DistributedLocks locks, Clock clock) {
    this.clickLogs = clickLogs;
    this.locks = locks;
    this.clock = clock;
  }

  /** Every day at 00:10 UTC. */
  @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
  public void scheduled() throws InterruptedException {
    if (!locks.run(LOCK, 0, 300, () -> maintain(LocalDate.now(clock)))) {
      LOG.info("Click log partition maintenance skipped: another instance holds the lock");
    }
  }

  /** Creates and drops partitions for the given UTC date. */
  public void maintain(LocalDate todayUtc) {
    TenantScope.runSystem("maintain click log partitions", () -> maintainInScope(todayUtc));
  }

  private void maintainInScope(LocalDate todayUtc) {
    Set<String> existing = new HashSet<>(clickLogs.listPartitions());
    for (YearMonth month : ClickPartitions.required(todayUtc)) {
      String name = ClickPartitions.name(month);
      if (!existing.contains(name)) {
        clickLogs.createPartition(name, ClickPartitions.from(month), ClickPartitions.to(month));
        clickLogs.commentPartition(name, month.toString());
        LOG.info("Created click log partition {}", name);
      }
    }
    for (String name : ClickPartitions.expired(existing, todayUtc)) {
      clickLogs.detachPartition(name);
      clickLogs.dropPartition(name);
      LOG.info("Dropped click log partition {}", name);
    }
  }
}
