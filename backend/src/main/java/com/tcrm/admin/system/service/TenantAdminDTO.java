package com.tcrm.admin.system.service;

import com.tcrm.admin.system.model.MemberRow;
import java.time.Instant;

/** A super administrator of one company; status and times are those of the membership. */
public record TenantAdminDTO(
    Long id,
    String username,
    String nickname,
    String email,
    Integer status,
    Instant lastLoginTime,
    Instant createTime) {
  public static TenantAdminDTO from(MemberRow member) {
    return new TenantAdminDTO(
        member.getUserId(),
        member.getUsername(),
        member.getNickname(),
        member.getEmail(),
        member.getStatus(),
        member.getLastLoginTime(),
        member.getCreateTime());
  }
}
