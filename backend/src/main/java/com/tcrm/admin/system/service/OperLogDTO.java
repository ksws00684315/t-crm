package com.tcrm.admin.system.service;

import java.time.Instant;

public record OperLogDTO(
    Long id,
    String module,
    String action,
    String operatorName,
    String method,
    String path,
    String summary,
    boolean success,
    Instant operTime,
    String ip,
    Long userId) {}
