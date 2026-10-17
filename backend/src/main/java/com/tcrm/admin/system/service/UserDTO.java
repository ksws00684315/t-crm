package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.model.MemberRow;
import java.time.Instant;
import java.util.List;

/**
 * A member as seen inside one company: role, data scope, status and codes are those of the
 * membership; {@code companyCount} is the number of companies the person belongs to.
 */
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
    List<String> permissions,
    int companyCount) {
  public static UserDTO from(MemberRow member, List<String> permissions, int companyCount) {
    return new UserDTO(
        member.getUserId(),
        member.getUsername(),
        member.getNickname(),
        member.getEmail(),
        member.getRole(),
        member.getDataScope(),
        member.getStatus(),
        member.getLastLoginTime(),
        member.getCreateTime(),
        member.getPostTemplate(),
        permissions == null ? List.of() : List.copyOf(permissions),
        companyCount);
  }
}
