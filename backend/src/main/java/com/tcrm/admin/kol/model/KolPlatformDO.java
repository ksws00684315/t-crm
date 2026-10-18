package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

/** KOL ↔ link-template multi-select row (crm_kol_platform, task 25a). */
@TableName("crm_kol_platform")
public class KolPlatformDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private Long kolId;
  private Long linkTemplateId;

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

  public Long getLinkTemplateId() {
    return linkTemplateId;
  }

  public void setLinkTemplateId(Long value) {
    this.linkTemplateId = value;
  }
}
