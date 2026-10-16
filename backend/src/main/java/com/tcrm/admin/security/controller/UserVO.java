package com.tcrm.admin.security.controller;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
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
    PostTemplate postTemplate,
    List<String> permissions) {
  public static UserVO from(AccountDTO u) {
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
        u.postTemplate(),
        u.effectivePermissions());
  }
}
