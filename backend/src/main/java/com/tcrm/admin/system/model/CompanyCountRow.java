package com.tcrm.admin.system.model;

/** Number of live memberships of one account, across every company. */
public class CompanyCountRow {
  private Long userId;
  private Integer companyCount;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public Integer getCompanyCount() {
    return companyCount;
  }

  public void setCompanyCount(Integer value) {
    this.companyCount = value;
  }
}
