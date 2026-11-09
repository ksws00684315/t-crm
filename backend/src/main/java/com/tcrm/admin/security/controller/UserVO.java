package com.tcrm.admin.security.controller;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import java.time.Instant;
import java.util.List;

/**
 * The signed-in identity. {@code permissions} are the codes this account may use in the current
 * company (ADR-038 §2.4): the caller resolves them through the permission service, so a super
 * administrator of the creator-library company holds its two codes only.
 */
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
   * (empty for a platform administrator) and {@code edition} the current company's edition.
   */
  public static UserVO from(
      AccountDTO u, List<CompanyDTO> companies, Edition edition, List<String> permissions) {
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
        permissions,
        companies.stream().map(CompanyVO::from).toList());
  }
}
