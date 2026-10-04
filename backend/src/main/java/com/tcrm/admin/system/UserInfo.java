package com.tcrm.admin.system;

import java.time.Instant;

public record UserInfo(
    Long id,
    String username,
    String nickname,
    String email,
    Role role,
    DataScope dataScope,
    Integer status,
    Instant lastLoginTime,
    Instant createTime) {
  public static UserInfo from(SysUser user) {
    return new UserInfo(
        user.id,
        user.username,
        user.nickname,
        user.email,
        user.role,
        user.dataScope,
        user.status,
        user.lastLoginTime,
        user.createTime);
  }
}
