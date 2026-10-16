package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ExternalAuditService implements ExternalAuditApi {
  private final SysOperLogMapper logs;
  private final CurrentUserContext current;

  public ExternalAuditService(SysOperLogMapper logs, CurrentUserContext current) {
    this.logs = logs;
    this.current = current;
  }

  @Override
  public void recordTenantOperation(
      long tenantId,
      long userId,
      String module,
      String action,
      String requestUri,
      String safeParams) {
    var actor = current.account(userId);
    SysOperLogDO row = new SysOperLogDO();
    row.setUserId(userId);
    row.setUsername(actor == null ? null : actor.username());
    row.setTenantId(tenantId);
    row.setModule(module);
    row.setAction(action);
    row.setMethod("GET");
    row.setRequestUri(requestUri);
    row.setRequestParams(safeParams);
    row.setResult("SUCCESS");
    row.setCostMs(0L);
    row.setOperTime(Instant.now());
    TenantScope.runSystem(
        "append public OAuth callback audit with explicit tenant", () -> logs.insert(row));
  }

  @Override
  public void recordKolOperation(
      long tenantId,
      String email,
      String module,
      String action,
      String requestUri,
      String ip,
      String safeParams) {
    SysOperLogDO row = new SysOperLogDO();
    // No sys_user is involved: the KOL email identifies the actor, the user id stays unset.
    row.setUsername(email);
    row.setTenantId(tenantId);
    row.setModule(module);
    row.setAction(action);
    row.setMethod("POST");
    row.setRequestUri(requestUri);
    row.setRequestParams(safeParams);
    row.setIp(AuditSanitizer.truncate(ip, 45));
    row.setResult("SUCCESS");
    row.setCostMs(0L);
    row.setOperTime(Instant.now());
    TenantScope.runSystem(
        "append KOL self-service audit with explicit tenant", () -> logs.insert(row));
  }
}
