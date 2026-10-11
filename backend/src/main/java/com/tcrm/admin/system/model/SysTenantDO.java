package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/** A company. This table has no tenant_id; it is the company registry itself. */
@TableName("sys_tenant")
public class SysTenantDO extends BaseEntity {
  private String tenantCode;
  private String name;
  private Integer status;
  private String remark;

  public String getTenantCode() {
    return tenantCode;
  }

  public void setTenantCode(String value) {
    this.tenantCode = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String value) {
    this.remark = value;
  }
}
