package com.tcrm.admin.security.api;

/**
 * A company the account may enter: enabled membership in an enabled company.
 *
 * @param tenantId company ID
 * @param tenantName company name
 * @param role the account's role in that company, SUPER_ADMIN or STAFF
 */
public record CompanyDTO(Long tenantId, String tenantName, Role role) {}
