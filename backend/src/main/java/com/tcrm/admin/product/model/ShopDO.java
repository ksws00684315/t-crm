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
}
