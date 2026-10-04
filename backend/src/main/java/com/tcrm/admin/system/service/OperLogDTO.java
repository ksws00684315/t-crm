package com.tcrm.admin.system.service;

import com.tcrm.admin.system.model.SysOperLogDO;
import java.time.Instant;

public record OperLogDTO(
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
  static OperLogDTO from(SysOperLogDO l) {
    return new OperLogDTO(
        l.getId(),
        l.getUserId(),
        l.getUsername(),
        l.getModule(),
        l.getAction(),
        l.getMethod(),
        l.getRequestUri(),
        l.getRequestParams(),
        l.getResult(),
        l.getErrorMsg(),
        l.getIp(),
        l.getCostMs(),
        l.getOperTime());
  }
}
