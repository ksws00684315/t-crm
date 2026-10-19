package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.service.OperLogDTO;
import java.time.Instant;

public record OperLogVO(
    Long id,
    String module,
    String action,
    String operatorName,
    String method,
    String path,
    String summary,
    boolean success,
    Instant operTime,
    String ip) {
  static OperLogVO from(OperLogDTO l) {
    return new OperLogVO(
        l.id(),
        l.module(),
        l.action(),
        l.operatorName(),
        l.method(),
        l.path(),
        l.summary(),
        l.success(),
        l.operTime(),
        l.ip());
  }
}
