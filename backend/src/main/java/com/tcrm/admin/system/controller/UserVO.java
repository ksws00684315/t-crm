package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.UserDTO;
import java.time.Instant;

public record UserVO(
    Long id,
    String username,
    String nickname,
    String email,
    Role role,
    DataScope dataScope,
    Integer status,
    Instant lastLoginTime,
    Instant createTime) {
  public static UserVO from(UserDTO u) {
    return new UserVO(
        u.id(),
        u.username(),
        u.nickname(),
        u.email(),
        u.role(),
        u.dataScope(),
        u.status(),
        u.lastLoginTime(),
        u.createTime());
  }
}
