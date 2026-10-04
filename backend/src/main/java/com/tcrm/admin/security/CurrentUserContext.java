package com.tcrm.admin.security;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.common.BusinessException;
import com.tcrm.admin.system.*;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserContext {
  private final SysUserMapper users;

  public CurrentUserContext(SysUserMapper users) {
    this.users = users;
  }

  public SysUser user() {
    Long id = StpUtil.getLoginIdAsLong();
    SysUser user = users.selectById(id);
    if (user == null || !Integer.valueOf(1).equals(user.status)) {
      StpUtil.kickout(id);
      throw new BusinessException(401, "Account disabled or unavailable");
    }
    return user;
  }

  public Long id() {
    return user().id;
  }

  public Role role() {
    return user().role;
  }

  public DataScope dataScope() {
    return user().dataScope;
  }
}
