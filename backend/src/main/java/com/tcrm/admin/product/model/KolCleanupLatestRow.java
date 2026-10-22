package com.tcrm.admin.product.model;

import java.time.Instant;

/** One cleanup row used to pick the latest record of a creator-item mapping. */
public class KolCleanupLatestRow {
  private Long kolItemId;
  private String status;
  private String reason;
  private String failReason;
  private Instant cleanupTime;

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String value) {
    this.reason = value;
  }

  public String getFailReason() {
    return failReason;
  }

  public void setFailReason(String value) {
    this.failReason = value;
  }

  public Instant getCleanupTime() {
    return cleanupTime;
  }

  public void setCleanupTime(Instant value) {
    this.cleanupTime = value;
  }
}
