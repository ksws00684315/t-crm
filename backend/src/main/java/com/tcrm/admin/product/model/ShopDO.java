package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;

@TableName("crm_shop")
public class ShopDO extends BaseEntity {
  private String shopName;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String shopCode;

  private String channel;

  private Integer maxItems;

  private Integer currentItems;

  private java.time.Instant itemsRefreshedAt;

  @TableField("is_source")
  private Boolean source;

  private String copyPoolStatus;

  private Boolean productApiOk;

  private java.time.Instant productApiCheckedAt;

  private String productApiError;

  private Boolean orderApiOk;

  private java.time.Instant orderApiCheckedAt;

  private String orderApiError;

  private Boolean refundApiOk;

  private java.time.Instant refundApiCheckedAt;

  private String refundApiError;

  /** Immutable after create; never written by entity updates. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String weidianShopId;

  private Integer status;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String remark;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public String getShopName() {
    return shopName;
  }

  public String getShopCode() {
    return shopCode;
  }

  public void setShopCode(String value) {
    shopCode = value;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String value) {
    channel = value;
  }

  public Integer getMaxItems() {
    return maxItems;
  }

  public void setMaxItems(Integer value) {
    maxItems = value;
  }

  public Integer getCurrentItems() {
    return currentItems;
  }

  public void setCurrentItems(Integer value) {
    currentItems = value;
  }

  public java.time.Instant getItemsRefreshedAt() {
    return itemsRefreshedAt;
  }

  public void setItemsRefreshedAt(java.time.Instant value) {
    itemsRefreshedAt = value;
  }

  public Boolean getSource() {
    return source;
  }

  public void setSource(Boolean value) {
    source = value;
  }

  public String getCopyPoolStatus() {
    return copyPoolStatus;
  }

  public void setCopyPoolStatus(String value) {
    copyPoolStatus = value;
  }

  public Boolean getProductApiOk() {
    return productApiOk;
  }

  public void setProductApiOk(Boolean value) {
    productApiOk = value;
  }

  public java.time.Instant getProductApiCheckedAt() {
    return productApiCheckedAt;
  }

  public void setProductApiCheckedAt(java.time.Instant value) {
    productApiCheckedAt = value;
  }

  public String getProductApiError() {
    return productApiError;
  }

  public void setProductApiError(String value) {
    productApiError = value;
  }

  public Boolean getOrderApiOk() {
    return orderApiOk;
  }

  public void setOrderApiOk(Boolean value) {
    orderApiOk = value;
  }

  public java.time.Instant getOrderApiCheckedAt() {
    return orderApiCheckedAt;
  }

  public void setOrderApiCheckedAt(java.time.Instant value) {
    orderApiCheckedAt = value;
  }

  public String getOrderApiError() {
    return orderApiError;
  }

  public void setOrderApiError(String value) {
    orderApiError = value;
  }

  public Boolean getRefundApiOk() {
    return refundApiOk;
  }

  public void setRefundApiOk(Boolean value) {
    refundApiOk = value;
  }

  public java.time.Instant getRefundApiCheckedAt() {
    return refundApiCheckedAt;
  }

  public void setRefundApiCheckedAt(java.time.Instant value) {
    refundApiCheckedAt = value;
  }

  public String getRefundApiError() {
    return refundApiError;
  }

  public void setRefundApiError(String value) {
    refundApiError = value;
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

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
