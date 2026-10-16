package com.tcrm.admin.order.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;

/** Persisted order_item; external raw payloads and buyer details are never stored. */
@TableName("crm_order_item")
public class OrderItemDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long orderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String subOrderId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String weidianItemId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String skuId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String itemName;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String skuName;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer quantity;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal payAmount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal refundAmount;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long kolId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long productId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long kolItemId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer attributionType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Instant attributionTime;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String manualReason;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getOrderId() {
    return orderId;
  }

  public void setOrderId(Long value) {
    orderId = value;
  }

  public String getSubOrderId() {
    return subOrderId;
  }

  public void setSubOrderId(String value) {
    subOrderId = value;
  }

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    weidianItemId = value;
  }

  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String value) {
    skuId = value;
  }

  public String getItemName() {
    return itemName;
  }

  public void setItemName(String value) {
    itemName = value;
  }

  public String getSkuName() {
    return skuName;
  }

  public void setSkuName(String value) {
    skuName = value;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer value) {
    quantity = value;
  }

  public BigDecimal getPayAmount() {
    return payAmount;
  }

  public void setPayAmount(BigDecimal value) {
    payAmount = value;
  }

  public BigDecimal getRefundAmount() {
    return refundAmount;
  }

  public void setRefundAmount(BigDecimal value) {
    refundAmount = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    kolId = value;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    productId = value;
  }

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    kolItemId = value;
  }

  public Integer getAttributionType() {
    return attributionType;
  }

  public void setAttributionType(Integer value) {
    attributionType = value;
  }

  public Instant getAttributionTime() {
    return attributionTime;
  }

  public void setAttributionTime(Instant value) {
    attributionTime = value;
  }

  public String getManualReason() {
    return manualReason;
  }

  public void setManualReason(String value) {
    manualReason = value;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    tenantId = value;
  }
}
