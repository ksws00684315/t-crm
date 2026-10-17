package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledSuperAdminApi;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Enabled super administrators of the current company (ADR-022). The role lives on the membership,
 * so the query reads {@code sys_user_tenant} for this company only; the account must be enabled
 * too. Without a company scope there is no company to read, so the call is refused.
 */
@Service
public class EnabledSuperAdminService implements EnabledSuperAdminApi {
  private final SysUserTenantMapper members;

  public EnabledSuperAdminService(SysUserTenantMapper members) {
    this.members = members;
  }

  @Override
  public List<Long> listEnabledIds() {
    Long tenantId = TenantScope.companyId();
    if (tenantId == null) {
      throw new IllegalStateException("enabled super administrators are listed in a company scope");
    }
    return members.listEnabledSuperAdminIds(tenantId);
  }
}
