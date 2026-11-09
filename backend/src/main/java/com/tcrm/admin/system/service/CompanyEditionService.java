package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.CompanyStore;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.system.mapper.SysTenantMapper;
import com.tcrm.admin.system.model.SysTenantDO;
import org.springframework.stereotype.Service;

/**
 * Reads the company's edition for authorisation (ADR-038). {@code sys_tenant} is ignored by the
 * tenant line, so the lookup works while the request's company scope is already open (the edition
 * check runs after the company header check) and for platform requests.
 */
@Service
public class CompanyEditionService implements CompanyStore {
  private final SysTenantMapper tenants;

  public CompanyEditionService(SysTenantMapper tenants) {
    this.tenants = tenants;
  }

  @Override
  public Edition editionOf(long tenantId) {
    SysTenantDO row = tenants.selectById(tenantId);
    // An unknown stored value reads back as null (LenientEnumTypeHandler): treat it as the full
    // product rather than locking a company out of everything.
    return row == null || row.getEdition() == null ? Edition.DISTRIBUTION : row.getEdition();
  }
}
