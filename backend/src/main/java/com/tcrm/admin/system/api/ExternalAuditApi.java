package com.tcrm.admin.system.api;

/** Minimal append-only audit hook for callers without administrator request credentials. */
public interface ExternalAuditApi {
  void recordTenantOperation(
      long tenantId,
      long userId,
      String module,
      String action,
      String requestUri,
      String safeParams);

  /**
   * Appends a successful company write operation inside the caller's transaction. Parameters must
   * be sanitized and contain no credentials or personal contact details.
   *
   * @param method HTTP method, or SYSTEM for an internal operation
   */
  void recordTenantWriteOperation(
      long tenantId,
      long userId,
      String module,
      String action,
      String method,
      String requestUri,
      String safeParams);

  /**
   * Records a KOL self-service operation (activation, password change, reset; task 12a §4.4). The
   * actor is a crm_kol row, not a sys_user, so the account is named by its email; include the KOL
   * id in {@code safeParams}, which must never contain passwords, tokens or links.
   */
  void recordKolOperation(
      long tenantId,
      String email,
      String module,
      String action,
      String requestUri,
      String ip,
      String safeParams);
}
