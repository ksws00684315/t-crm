package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.system.api.EnabledTenantApi;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EnabledTenantService implements EnabledTenantApi {
  private final SysTenantMapper tenants;

  public EnabledTenantService(SysTenantMapper tenants) {
    this.tenants = tenants;
  }

  @Override
  public List<Long> enabledTenantIds() {
    return tenants
        .selectList(new LambdaQueryWrapper<SysTenantDO>().eq(SysTenantDO::getStatus, 1))
        .stream()
        .map(SysTenantDO::getId)
        .toList();
  }
}
