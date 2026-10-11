package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.service.TenantAdminDTO;
import java.time.Instant;

public record TenantAdminVO(
    Long id,
    String username,
    String nickname,
    String email,
    Integer status,
    Instant lastLoginTime,
    Instant createTime) {
  public static TenantAdminVO from(TenantAdminDTO user) {
    return new TenantAdminVO(
        user.id(),
        user.username(),
        user.nickname(),
        user.email(),
        user.status(),
        user.lastLoginTime(),
        user.createTime());
  }
}
