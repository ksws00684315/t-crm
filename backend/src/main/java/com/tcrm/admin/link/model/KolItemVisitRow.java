package com.tcrm.admin.link.model;

/** Aggregate row for {@link com.tcrm.admin.link.mapper.ClickLogMapper#countVisitsByKolItemIds}. */
public class KolItemVisitRow {
  private Long kolItemId;
  private Long visitCount;

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public Long getVisitCount() {
    return visitCount;
  }

  public void setVisitCount(Long value) {
    this.visitCount = value;
  }
}
