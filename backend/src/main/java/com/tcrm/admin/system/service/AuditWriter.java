package com.tcrm.admin.system.service;

import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {
  private static final Logger LOG = LoggerFactory.getLogger(AuditWriter.class);
  private final SysOperLogMapper logs;

  public AuditWriter(SysOperLogMapper logs) {
    this.logs = logs;
  }

  @Async
  public void write(SysOperLogDO event) {
    try {
      logs.insert(event);
    } catch (RuntimeException e) {
      LOG.error("Audit persistence failed", e);
    }
  }
}
