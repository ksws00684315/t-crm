package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Append-only promotion change row (crm_kol_promotion_record, task 25a). No logical delete. */
@TableName("crm_kol_promotion_record")
public class KolPromotionRecordDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;
  private String changeType;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String beforeJson;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String afterJson;

  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

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

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public String getChangeType() {
    return changeType;
  }

  public void setChangeType(String value) {
    this.changeType = value;
  }

  public String getBeforeJson() {
    return beforeJson;
  }

  public void setBeforeJson(String value) {
    this.beforeJson = value;
  }

  public String getAfterJson() {
    return afterJson;
  }

  public void setAfterJson(String value) {
    this.afterJson = value;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String value) {
    this.remark = value;
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
}
