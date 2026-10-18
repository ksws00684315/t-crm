package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

@TableName("crm_shop")
public class ShopDO extends BaseEntity {
  private String shopName;

  /** Immutable after create; never written by entity updates. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String weidianShopId;

  private Integer status;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String remark;

  /** Default copy-target shop for the company (ADR-019 / task 17b). */
  private Boolean copyTarget;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public String getShopName() {
    return shopName;
  }

  public void setShopName(String value) {
    this.shopName = value;
  }

  public String getWeidianShopId() {
    return weidianShopId;
  }

  public void setWeidianShopId(String value) {
    this.weidianShopId = value;
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

  public Boolean getCopyTarget() {
    return copyTarget;
  }

  public void setCopyTarget(Boolean value) {
    this.copyTarget = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
