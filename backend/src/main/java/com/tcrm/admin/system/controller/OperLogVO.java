package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.service.OperLogDTO;
import java.time.Instant;

public record OperLogVO(
    Long id,
    Long userId,
    String username,
    String module,
    String action,
    String method,
    String requestUri,
    String requestParams,
    String result,
    String errorMsg,
    String ip,
    Long costMs,
    Instant operTime) {
  static OperLogVO from(OperLogDTO l) {
    return new OperLogVO(
        l.id(),
        l.userId(),
        l.username(),
        l.module(),
        l.action(),
        l.method(),
        l.requestUri(),
        l.requestParams(),
        l.result(),
        l.errorMsg(),
        l.ip(),
        l.costMs(),
        l.operTime());
  }
}
