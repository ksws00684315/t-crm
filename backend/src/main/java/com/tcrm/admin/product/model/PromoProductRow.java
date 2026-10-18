package com.tcrm.admin.product.model;

/** An original product found by shop + Weidian item ID (task 26 promo-sheet upload). */
public class PromoProductRow {
  private String weidianItemId;
  private Long productId;
  private String productCode;

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    this.productId = value;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String value) {
    this.productCode = value;
  }
}
