package com.tcrm.admin.system.api;

/**
 * Administrator seen as a BD (business development) owner; never carries credentials.
 *
 * @param id administrator ID
 * @param username login name
 * @param nickname display name
 * @param usable true when the account is enabled, not deleted, role OPERATOR and data scope OWN
 */
public record BdUserDTO(Long id, String username, String nickname, boolean usable) {}
