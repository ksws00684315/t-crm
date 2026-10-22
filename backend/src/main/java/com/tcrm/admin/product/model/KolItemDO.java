package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * Exclusive-item mapping. No logical delete: mappings are only closed by setting {@code validTo}.
 * Audit fields are filled manually (table has no is_deleted, so it does not extend BaseEntity).
 */
@TableName("crm_kol_item")
public class KolItemDO {
  @TableId private Long id;
  private Long kolId;
  private Long productId;
  private Long shopId;
  private String weidianItemId;
  private String itemName;
  private String source;

  /** Company-unique link code. Inserts set it; updates never write it. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String linkCode;

  private Instant validFrom;
  private Instant validTo;

  @TableField(fill = FieldFill.INSERT)
  private Long createBy;

  @TableField(fill = FieldFill.INSERT)
  private Instant createTime;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Long updateBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updateTime;

  /** Owning company. Updates never write this column. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    this.productId = value;
  }

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    this.shopId = value;
  }

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public String getItemName() {
    return itemName;
  }

  public void setItemName(String value) {
    this.itemName = value;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String value) {
    this.source = value;
  }

  public String getLinkCode() {
    return linkCode;
  }

  public void setLinkCode(String value) {
    this.linkCode = value;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public void setValidFrom(Instant value) {
    this.validFrom = value;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public void setValidTo(Instant value) {
    this.validTo = value;
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

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }
}
