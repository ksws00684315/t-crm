package com.tcrm.admin.task.controller;

import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.model.TaskStatus;
import com.tcrm.admin.task.service.TaskDTO;
import java.time.Instant;

/** Task progress; {@code done} counts processed rows including failed ones. */
public record TaskVO(
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
  public static TaskVO from(TaskDTO t) {
    return new TaskVO(
        t.id(),
        t.taskType(),
        t.status(),
        t.total(),
        t.done(),
        t.failed(),
        t.message(),
        t.hasErrorFile(),
        t.createTime(),
        t.startedAt(),
        t.finishedAt());
  }
}
