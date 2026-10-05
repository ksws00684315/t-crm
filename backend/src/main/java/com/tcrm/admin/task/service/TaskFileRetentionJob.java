package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.model.TaskFileDO;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes task files older than {@link #RETENTION_DAYS} days (task 05 §4.5). Task records are kept;
 * downloading a cleaned error file returns {@code TASK_FILE_EXPIRED}.
 */
@Component
public class TaskFileRetentionJob {
  /** Retention for crm_task_file rows; not configurable (task 05 §4.5). */
  public static final int RETENTION_DAYS = 30;

  static final String LOCK = "task:file-retention";
  private static final Logger LOG = LoggerFactory.getLogger(TaskFileRetentionJob.class);

  private final TaskFileMapper files;
  private final DistributedLocks locks;

  public TaskFileRetentionJob(TaskFileMapper files, DistributedLocks locks) {
    this.files = files;
    this.locks = locks;
  }

  /** Every day at 03:30 UTC. */
  @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
  public void run() throws InterruptedException {
    locks.run(LOCK, 0, 600, this::cleanup);
  }

  /**
   * Deletes files whose create_time is strictly before now − retention. Package-visible for tests.
   */
  void cleanup() {
    Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
    int deleted =
        files.delete(new LambdaQueryWrapper<TaskFileDO>().lt(TaskFileDO::getCreateTime, cutoff));
    if (deleted > 0) {
      LOG.info(
          "Deleted {} task files older than {} days (before {})", deleted, RETENTION_DAYS, cutoff);
    }
  }

  /** Cutoff instant used by {@link #cleanup} at the given "now". Package-visible for tests. */
  static Instant cutoff(Instant now) {
    return now.minus(RETENTION_DAYS, ChronoUnit.DAYS);
  }
}
