package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Tasks live only in this process's queue, so WAITING / RUNNING tasks left by a previous run can
 * never finish; mark them FAILED on startup. Assumes a single backend instance (current
 * deployment).
 */
@Component
public class InterruptedTaskCleaner implements ApplicationRunner {
  static final String MESSAGE = "Interrupted by restart";
  private static final Logger LOG = LoggerFactory.getLogger(InterruptedTaskCleaner.class);
  private final TaskMapper tasks;

  public InterruptedTaskCleaner(TaskMapper tasks) {
    this.tasks = tasks;
  }

  @Override
  public void run(ApplicationArguments args) {
    Instant now = Instant.now();
    int count =
        TenantScope.callSystem(
            "mark interrupted tasks failed across companies",
            () ->
                tasks.update(
                    null,
                    new LambdaUpdateWrapper<TaskDO>()
                        .set(TaskDO::getStatus, TaskStatus.FAILED)
                        .set(TaskDO::getMessage, MESSAGE)
                        .set(TaskDO::getFinishedAt, now)
                        .set(TaskDO::getUpdateTime, now)
                        .in(TaskDO::getStatus, List.of(TaskStatus.WAITING, TaskStatus.RUNNING))));
    if (count > 0) {
      LOG.warn("Marked {} interrupted batch tasks as failed", count);
    }
  }
}
