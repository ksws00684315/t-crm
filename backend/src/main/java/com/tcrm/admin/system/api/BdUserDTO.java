package com.tcrm.admin.system.api;

/**
 * Administrator seen as a BD (business development) owner; never carries credentials.
 *
 * @param id administrator ID
 * @param username login name
 * @param nickname display name
 * @param usable true when the account is enabled, staff, OWN scope, and has {@code kol:edit}
 */
public record BdUserDTO(Long id, String username, String nickname, boolean usable) {}
