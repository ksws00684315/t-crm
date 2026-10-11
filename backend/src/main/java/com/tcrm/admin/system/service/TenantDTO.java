package com.tcrm.admin.system.service;

import com.tcrm.admin.system.model.SysTenantDO;
import java.time.Instant;

public record TenantDTO(
    Long id, String tenantCode, String name, Integer status, String remark, Instant createTime) {
  public static TenantDTO from(SysTenantDO row) {
    return new TenantDTO(
        row.getId(),
        row.getTenantCode(),
        row.getName(),
        row.getStatus(),
        row.getRemark(),
        row.getCreateTime());
  }
}
