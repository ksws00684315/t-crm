package com.tcrm.admin.product.model;

/**
 * Result row of the current-link queries in {@code KolItemMapper} (mapping joined with product).
 */
public class KolItemLinkRow {
  private Long kolItemId;
  private Long kolId;
  private Long productId;
  private String productCode;
  private String productName;
  private Long shopId;
  private String weidianItemId;
  private Integer productStatus;
  private Integer shopStatus;

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
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

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String value) {
    this.productCode = value;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String value) {
    this.productName = value;
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

  public Integer getProductStatus() {
    return productStatus;
  }

  public void setProductStatus(Integer value) {
    this.productStatus = value;
  }

  public Integer getShopStatus() {
    return shopStatus;
  }

  public void setShopStatus(Integer value) {
    this.shopStatus = value;
  }
}
