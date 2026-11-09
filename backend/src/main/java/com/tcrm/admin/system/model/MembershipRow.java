package com.tcrm.admin.system.model;

import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.Role;

/** One membership of an account with its company name, status and edition. */
public class MembershipRow {
  private Long tenantId;
  private String tenantName;
  private Integer tenantStatus;
  private Edition tenantEdition;
  private Role role;
  private Integer status;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public String getTenantName() {
    return tenantName;
  }

  public void setTenantName(String value) {
    this.tenantName = value;
  }

  public Edition getTenantEdition() {
    return tenantEdition;
  }

  public void setTenantEdition(Edition value) {
    this.tenantEdition = value;
  }

  public Integer getTenantStatus() {
    return tenantStatus;
  }

  public void setTenantStatus(Integer value) {
    this.tenantStatus = value;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role value) {
    this.role = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }
}
