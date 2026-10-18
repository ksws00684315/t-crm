package com.tcrm.admin.product.model;

/** One current mapping of a KOL with its shop's Weidian id (task 26). */
public class KolItemShopRow {
  private String weidianItemId;
  private String weidianShopId;

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public String getWeidianShopId() {
    return weidianShopId;
  }

  public void setWeidianShopId(String value) {
    this.weidianShopId = value;
  }
}
