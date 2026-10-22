package com.tcrm.admin.system.service;

import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledPrepayReviewerApi;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** Company-local recipient lookup for prepay submission notifications. */
@Service
public class EnabledPrepayReviewerService implements EnabledPrepayReviewerApi {
  private final SysUserTenantMapper members;

  public EnabledPrepayReviewerService(SysUserTenantMapper members) {
    this.members = members;
  }

  @Override
  public List<Long> listEnabledIds() {
    Long tenantId = TenantScope.companyId();
    if (tenantId == null) {
      throw new IllegalStateException("prepay reviewers are listed in a company scope");
    }
    return members.listEnabledPrepayReviewerIds(tenantId);
  }
}
