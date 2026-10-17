package com.tcrm.admin.security.service;

import com.tcrm.admin.shared.AuditLog;
import org.springframework.stereotype.Component;

/**
 * Audit point of a company switch. A separate bean so {@link AuditLog} sees the source company as
 * the argument; it runs after the token moved, so the row is attributed to the target company.
 */
@Component
public class CompanySwitchAudit {
  /** Source company of a switch. */
  public record Switched(Long fromTenantId) {}

  @AuditLog(module = "Auth", action = "Switch company")
  public void switched(Switched change) {
    // The aspect records the call; there is nothing else to do.
  }
}
