package com.tcrm.admin.shared;

/**
 * Logged-in administrator's company. Implemented in the security module so shared stays free of
 * login types. Returns null when nobody is logged in or the account belongs to no company.
 */
public interface CurrentTenantResolver {
  Long currentLoginTenantId();
}
