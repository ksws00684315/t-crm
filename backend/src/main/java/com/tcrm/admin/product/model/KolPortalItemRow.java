package com.tcrm.admin.product.model;

import java.time.Instant;

/** One row of the KOL portal item list (task 26). */
public class KolPortalItemRow {
  private Long id;
  private String weidianItemId;
  private String productName;
  private String itemName;
  private String imageUrl;
  private Instant createTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String value) {
    this.productName = value;
  }

  public String getItemName() {
    return itemName;
  }

  public void setItemName(String value) {
    this.itemName = value;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String value) {
    this.imageUrl = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
