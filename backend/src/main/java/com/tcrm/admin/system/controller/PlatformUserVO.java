package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.PlatformUserDTO;
import java.util.List;

/** {@code email} is always present in the response, null when the account has none (21b). */
public record PlatformUserVO(
    Long id,
    String username,
    String nickname,
    String email,
    Integer status,
    List<Company> companies) {
  public record Company(Long tenantId, String tenantName, Role role, Integer status) {}

  static PlatformUserVO from(PlatformUserDTO u) {
    if (u == null) {
      return null;
    }
    return new PlatformUserVO(
        u.id(),
        u.username(),
        u.nickname(),
        u.email(),
        u.status(),
        u.companies().stream()
            .map(c -> new Company(c.tenantId(), c.tenantName(), c.role(), c.status()))
            .toList());
  }
}
