package com.tcrm.admin.product.api;

/**
 * Per-company daily allowance of Weidian {@code vdian.item.add} calls (task 138 follow-up, task
 * 139). The company is the current {@code TenantScope} company.
 */
public interface CopyDailyQuotaApi {
  /**
   * Counts one {@code vdian.item.add} call for today (Beijing date) if the company still has room.
   *
   * @return false when today's limit is already reached; nothing is counted then
   */
  boolean tryAcquire();
}
