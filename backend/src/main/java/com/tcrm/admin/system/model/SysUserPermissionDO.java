package com.tcrm.admin.system.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** One granted permission code. Append-and-delete; no update columns and no logical delete. */
@TableName("sys_user_permission")
public class SysUserPermissionDO {
  @TableId private Long id;
  private Long userId;
  private String permissionCode;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    this.userId = value;
  }

  public String getPermissionCode() {
    return permissionCode;
  }

  public void setPermissionCode(String value) {
    this.permissionCode = value;
  }

  public Long getCreateBy() {
    return createBy;
  }

  public void setCreateBy(Long value) {
    this.createBy = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
