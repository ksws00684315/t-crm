package com.tcrm.admin.system.api;

/** Minimal append-only audit hook for public third-party callbacks without request credentials. */
public interface ExternalAuditApi {
  void recordTenantOperation(
      long tenantId,
      long userId,
      String module,
      String action,
      String requestUri,
      String safeParams);
}
