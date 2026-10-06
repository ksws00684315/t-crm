package com.tcrm.admin.link.model;

/**
 * Click counts of one link (KOL + original product, identified by the product within one KOL's
 * query) over a period: all clicks and valid ones (neither bot nor dup).
 */
public class ClickCountRow {
  private Long productId;
  private Long total;
  private Long valid;

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    this.productId = value;
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
