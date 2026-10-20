package com.tcrm.admin.system.api;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;

/**
 * Administrator seen as a BD (business development) owner; never carries credentials.
 *
 * @param id administrator ID
 * @param username login name
 * @param nickname display name
 * @param usable true when the account is enabled, staff, OWN scope, and has {@code kol:edit}
 * @param enabled true when the account and company membership are enabled
 */
public record BdUserDTO(
    Long id,
    String username,
    String nickname,
    boolean usable,
    Role role,
    DataScope dataScope,
    boolean enabled) {
  public BdUserDTO(
      Long id, String username, String nickname, boolean usable, Role role, DataScope dataScope) {
    this(id, username, nickname, usable, role, dataScope, usable);
  }

  public BdUserDTO(Long id, String username, String nickname, boolean usable) {
    this(id, username, nickname, usable, null, null, usable);
  }
}
