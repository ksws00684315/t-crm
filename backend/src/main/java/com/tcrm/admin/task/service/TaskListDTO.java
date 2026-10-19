package com.tcrm.admin.task.service;

import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.model.TaskStatus;
import java.time.Instant;

public record TaskListDTO(
    Long id,
    TaskType type,
    String fileName,
    TaskStatus status,
    int totalRows,
    int successRows,
    int failedRows,
    Instant createTime,
    Instant finishTime,
    String operatorName,
    boolean hasErrorFile) {}
