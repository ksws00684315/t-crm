package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.DepartureService;
import com.tcrm.admin.system.service.UserDTO;
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
    PostTemplate postTemplate,
    List<String> permissions,
    int companyCount,
    /** Newest departure plan of the account; null when it never had one (task 41 §2.2). */
    DeparturePlanVO departurePlan) {
  public static UserVO from(UserDTO u) {
    return from(u, null);
  }

  public static UserVO from(UserDTO u, DepartureService.PlanDTO plan) {
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
        u.postTemplate(),
        u.permissions(),
        u.companyCount(),
        DeparturePlanVO.from(plan));
  }
}
