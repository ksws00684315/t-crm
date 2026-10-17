package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** One account's membership of one company: role, data scope and status in that company. */
@TableName("sys_user_tenant")
public class SysUserTenantDO extends BaseEntity {
  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  /** The account. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long userId;

  private Role role;
  private PostTemplate postTemplate;
  private DataScope dataScope;
  private Integer status;
  private Instant lastLoginTime;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role value) {
    this.role = value;
  }

  public PostTemplate getPostTemplate() {
    return postTemplate;
  }

  public void setPostTemplate(PostTemplate value) {
    this.postTemplate = value;
  }

  public DataScope getDataScope() {
    return dataScope;
  }

  public void setDataScope(DataScope value) {
    this.dataScope = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }

  public Instant getLastLoginTime() {
    return lastLoginTime;
  }

  public void setLastLoginTime(Instant value) {
    this.lastLoginTime = value;
  }
}
