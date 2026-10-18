package com.tcrm.admin.product.model;

/** Current owner of one shop item (task 26 promo-sheet classification). */
public class KolItemOwnerRow {
  private String weidianItemId;
  private Long kolId;

  public String getWeidianItemId() {
    return weidianItemId;
  }

  public void setWeidianItemId(String value) {
    this.weidianItemId = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }
}
