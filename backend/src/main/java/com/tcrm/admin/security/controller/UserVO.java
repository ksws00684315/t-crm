package com.tcrm.admin.security.controller;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import java.time.Instant;
import java.util.List;

public record UserVO(
    Long id,
    String username,
    String nickname,
    String email,
    Role role,
    DataScope dataScope,
    Integer status,
    Instant lastLoginTime,
    Instant createTime,
    Long tenantId,
    String tenantName,
    Edition edition,
    PostTemplate postTemplate,
    List<String> permissions,
    List<CompanyVO> companies) {
  /**
   * Identity in the current company; {@code companies} are the account's usable companies by name
   * (empty for a platform administrator). {@code edition} is the edition of the current company
   * (ADR-038), which the admin UI uses for its menu and route guard; {@code permissions} are the
   * effective codes in that edition, so a super administrator of the creator-library edition holds
   * the two creator-library codes only.
   */
  public static UserVO from(AccountDTO u, List<CompanyDTO> companies, Edition edition) {
    return new UserVO(
        u.id(),
        u.username(),
        u.nickname(),
        u.email(),
        u.role(),
        u.dataScope(),
        u.status(),
        u.lastLoginTime(),
        u.createTime(),
        u.tenantId(),
        u.tenantName(),
        edition,
        u.postTemplate(),
        u.effectivePermissions(),
        companies.stream().map(CompanyVO::from).toList());
  }
}
