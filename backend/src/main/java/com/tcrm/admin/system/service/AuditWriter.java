package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.TenantScope;
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
      // The async thread has no login. tenant_id was chosen on the request thread.
      TenantScope.runSystem("persist audit log", () -> logs.insert(event));
    } catch (RuntimeException e) {
      LOG.error("Audit persistence failed", e);
    }
  }
}
