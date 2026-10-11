package com.tcrm.admin.system.service;

import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;

public record TenantAdminDTO(
    Long id,
    String username,
    String nickname,
    String email,
    Integer status,
    Instant lastLoginTime,
    Instant createTime) {
  public static TenantAdminDTO from(SysUserDO user) {
    return new TenantAdminDTO(
        user.getId(),
        user.getUsername(),
        user.getNickname(),
        user.getEmail(),
        user.getStatus(),
        user.getLastLoginTime(),
        user.getCreateTime());
  }
}
