package com.tcrm.admin.link.model;

/** Click counts of one link over a period: all clicks and valid ones (neither bot nor dup). */
public class ClickCountRow {
  private Long kolItemId;
  private Long total;
  private Long valid;

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public Long getTotal() {
    return total;
  }

  public void setTotal(Long value) {
    this.total = value;
  }

  public Long getValid() {
    return valid;
  }

  public void setValid(Long value) {
    this.valid = value;
  }
}
