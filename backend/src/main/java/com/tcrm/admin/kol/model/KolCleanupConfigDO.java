package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Per-company idle-KOL cleanup settings (crm_kol_cleanup_config, task 25a). */
@TableName("crm_kol_cleanup_config")
public class KolCleanupConfigDO {
  @TableId private Long id;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Boolean enabled;
  private Integer idleDays;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Long updateBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
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

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean value) {
    this.enabled = value;
  }

  public Integer getIdleDays() {
    return idleDays;
  }

  public void setIdleDays(Integer value) {
    this.idleDays = value;
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
