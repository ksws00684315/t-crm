package com.tcrm.admin.task.service;

import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskStatus;
import java.time.Instant;

public record TaskDTO(
    Long id,
    TaskType taskType,
    TaskStatus status,
    int total,
    int done,
    int failed,
    String message,
    boolean hasErrorFile,
    Instant createTime,
    Instant startedAt,
    Instant finishedAt) {
  static TaskDTO from(TaskDO task, boolean hasErrorFile) {
    return new TaskDTO(
        task.getId(),
        task.getTaskType(),
        task.getStatus(),
        task.getTotal(),
        task.getDone(),
        task.getFailed(),
        task.getMessage(),
        hasErrorFile,
        task.getCreateTime(),
        task.getStartedAt(),
        task.getFinishedAt());
  }
}
