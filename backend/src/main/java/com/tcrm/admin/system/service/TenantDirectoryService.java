package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.system.api.TenantDirectoryApi;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TenantDirectoryService implements TenantDirectoryApi {
  private final SysTenantMapper tenants;

  public TenantDirectoryService(SysTenantMapper tenants) {
    this.tenants = tenants;
  }

  @Override
  public List<TenantRefDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    // sys_tenant is always ignored by the tenant line handler, so no explicit scope is needed.
    return tenants
        .selectList(new LambdaQueryWrapper<SysTenantDO>().in(SysTenantDO::getId, ids))
        .stream()
        .map(
            t -> new TenantRefDTO(t.getId(), t.getName(), Integer.valueOf(1).equals(t.getStatus())))
        .toList();
  }
}
