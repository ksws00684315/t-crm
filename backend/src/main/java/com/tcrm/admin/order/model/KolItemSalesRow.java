package com.tcrm.admin.order.model;

import java.math.BigDecimal;

/** Aggregate row for {@link com.tcrm.admin.order.mapper.OrderItemMapper#sumByKolItemIds}. */
public class KolItemSalesRow {
  private Long kolItemId;
  private Long salesQuantity;
  private BigDecimal payAmount;
  private BigDecimal refundAmount;

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public Long getSalesQuantity() {
    return salesQuantity;
  }

  public void setSalesQuantity(Long value) {
    this.salesQuantity = value;
  }

  public BigDecimal getPayAmount() {
    return payAmount;
  }

  public void setPayAmount(BigDecimal value) {
    this.payAmount = value;
  }

  public BigDecimal getRefundAmount() {
    return refundAmount;
  }

  public void setRefundAmount(BigDecimal value) {
    this.refundAmount = value;
  }
}
