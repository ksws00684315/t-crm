package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One BD register link (task 12c). Revoking sets {@code status} to 0; the row stays. There is no
 * logical delete. The code is plaintext because it only selects the company and the owning BD.
 */
@TableName("crm_kol_register_code")
public class KolRegisterCodeDO {
  /** Active link. At most one row per BD has this status. */
  public static final int STATUS_ACTIVE = 1;

  /** Replaced by a newer code. */
  public static final int STATUS_REVOKED = 0;

  @TableId private Long id;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long bdUserId;
  private String code;
  private Integer status;
  private Long createBy;

  /** Creation time. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Instant createTime;

  private Long updateBy;
  private Instant updateTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public Long getBdUserId() {
    return bdUserId;
  }

  public void setBdUserId(Long value) {
    this.bdUserId = value;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String value) {
    this.code = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
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

  public Long getUpdateBy() {
    return updateBy;
  }

  public void setUpdateBy(Long value) {
    this.updateBy = value;
  }

  public Instant getUpdateTime() {
    return updateTime;
  }

  public void setUpdateTime(Instant value) {
    this.updateTime = value;
  }
}
