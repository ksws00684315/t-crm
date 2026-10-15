package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import java.util.List;

public record UserDTO(
    Long id,
    String username,
    String nickname,
    String email,
    Role role,
    DataScope dataScope,
    Integer status,
    Instant lastLoginTime,
    Instant createTime,
    PostTemplate postTemplate,
    List<String> permissions) {
  public static UserDTO from(SysUserDO user, List<String> permissions) {
    return new UserDTO(
        user.getId(),
        user.getUsername(),
        user.getNickname(),
        user.getEmail(),
        user.getRole(),
        user.getDataScope(),
        user.getStatus(),
        user.getLastLoginTime(),
        user.getCreateTime(),
        user.getPostTemplate(),
        permissions == null ? List.of() : List.copyOf(permissions));
  }
}
