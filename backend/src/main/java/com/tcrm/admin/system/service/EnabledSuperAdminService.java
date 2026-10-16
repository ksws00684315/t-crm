package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.api.EnabledSuperAdminApi;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads enabled super administrators. The tenant line limits the query to the current company. */
@Service
public class EnabledSuperAdminService implements EnabledSuperAdminApi {
  private final SysUserMapper users;

  public EnabledSuperAdminService(SysUserMapper users) {
    this.users = users;
  }

  @Override
  public List<Long> listEnabledIds() {
    return users
        .selectList(
            new LambdaQueryWrapper<SysUserDO>()
                .select(SysUserDO::getId)
                .eq(SysUserDO::getRole, Role.SUPER_ADMIN)
                .eq(SysUserDO::getStatus, 1)
                .orderByAsc(SysUserDO::getId))
        .stream()
        .map(SysUserDO::getId)
        .toList();
  }
}
