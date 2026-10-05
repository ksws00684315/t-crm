package com.tcrm.admin.product.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;

/**
 * Original product. {@code productCode}, {@code shopId} and {@code weidianItemId} are NEVER written
 * by entity updates; {@code category}, {@code imageUrl} and {@code rate} use ALWAYS so clears
 * survive updateById.
 */
@TableName("crm_product")
public class ProductDO extends BaseEntity {
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String productCode;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long shopId;

  @TableField(updateStrategy = FieldStrategy.NEVER)
  private String weidianItemId;

  private String name;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String category;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String imageUrl;

  private BigDecimal price;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal rate;

  private Integer status;

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String value) {
    this.productCode = value;
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

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String value) {
    this.category = value;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String value) {
    this.imageUrl = value;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal value) {
    this.price = value;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal value) {
    this.rate = value;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer value) {
    this.status = value;
  }
}
