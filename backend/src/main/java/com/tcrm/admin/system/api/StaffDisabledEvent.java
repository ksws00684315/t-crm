package com.tcrm.admin.system.api;

/**
 * Published in the account status transaction when a STAFF account is disabled. Subscribers must
 * use the explicit company ID and run before commit for atomic cleanup.
 *
 * @param tenantId owning company ID, positive
 * @param userId disabled staff account ID, positive
 * @param actorId account to credit in the audit rows; null means the disabled account itself (a
 *     system execution has no request credentials, task 41)
 */
public record StaffDisabledEvent(long tenantId, long userId, Long actorId) {
  /** Event of an administrator action; the audit actor is the disabled account unless given. */
  public StaffDisabledEvent(long tenantId, long userId) {
    this(tenantId, userId, null);
  }
}
