package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.service.TenantDTO;
import java.time.Instant;

public record TenantVO(
    Long id, String tenantCode, String name, Integer status, String remark, Instant createTime) {
  public static TenantVO from(TenantDTO row) {
    return new TenantVO(
        row.id(), row.tenantCode(), row.name(), row.status(), row.remark(), row.createTime());
  }
}
