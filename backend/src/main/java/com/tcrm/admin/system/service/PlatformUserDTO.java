package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.Role;
import java.util.List;

/**
 * One account as the platform administrator sees it: identity, account-wide status, and every live
 * membership. No business data.
 */
public record PlatformUserDTO(
    Long id, String username, String nickname, Integer status, List<Company> companies) {
  public PlatformUserDTO {
    companies = List.copyOf(companies);
  }

  /**
   * @param status membership status in that company
   */
  public record Company(Long tenantId, String tenantName, Role role, Integer status) {}
}
