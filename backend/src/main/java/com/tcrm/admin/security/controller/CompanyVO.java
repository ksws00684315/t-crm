package com.tcrm.admin.security.controller;

import com.tcrm.admin.security.api.CompanyDTO;
import com.tcrm.admin.security.api.Role;

public record CompanyVO(Long tenantId, String tenantName, Role role) {
  static CompanyVO from(CompanyDTO c) {
    return new CompanyVO(c.tenantId(), c.tenantName(), c.role());
  }
}
