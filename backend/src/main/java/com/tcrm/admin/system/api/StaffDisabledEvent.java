package com.tcrm.admin.system.api;

/**
 * Published in the account status transaction when a STAFF account is disabled. Subscribers must
 * use the explicit company ID and run before commit for atomic cleanup.
 *
 * @param tenantId owning company ID, positive
 * @param userId disabled staff account ID, positive
 */
public record StaffDisabledEvent(long tenantId, long userId) {}
