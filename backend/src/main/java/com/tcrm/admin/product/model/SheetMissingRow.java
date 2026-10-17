package com.tcrm.admin.product.model;

import java.time.Instant;

/** An original product that was in the source sheet before but not in the latest read. */
public class SheetMissingRow {
  private Long productId;
  private String productCode;
  private String productName;
  private Integer productStatus;
  private Long shopId;
  private String weidianItemId;
  private Integer sheetRow;
  private Instant lastSeenTime;

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    productId = value;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String value) {
    productCode = value;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String value) {
    productName = value;
  }

  public Integer getProductStatus() {
    return productStatus;
  }

  public void setProductStatus(Integer value) {
    productStatus = value;
  }

  public Long getShopId() {
    return shopId;
  }

  public void setShopId(Long value) {
    shopId = value;
  }

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    weidianItemId = value;
  }

  public Integer getSheetRow() {
    return sheetRow;
  }

  public void setSheetRow(Integer value) {
    sheetRow = value;
  }

  public Instant getLastSeenTime() {
    return lastSeenTime;
  }

  public void setLastSeenTime(Instant value) {
    lastSeenTime = value;
  }
}
