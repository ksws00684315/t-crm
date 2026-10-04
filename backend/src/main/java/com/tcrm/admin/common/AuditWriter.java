package com.tcrm.admin.common;

import com.tcrm.admin.system.OperLog;
import com.tcrm.admin.system.OperLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {
  private static final Logger LOG = LoggerFactory.getLogger(AuditWriter.class);
  private final OperLogMapper logs;

  public AuditWriter(OperLogMapper logs) {
    this.logs = logs;
  }

  @Async
  public void write(OperLog event) {
    try {
      logs.insert(event);
    } catch (RuntimeException e) {
      LOG.error("Audit persistence failed", e);
    }
  }
}
