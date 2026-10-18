package com.tcrm.admin.kol.model;

/** Stored promotion workflow status (ADR-023). PENDING_UPDATE is display-only. */
public enum PromotionStatus {
  PENDING_REVIEW,
  PENDING_PROMOTION,
  PROMOTING,
  ENDED;

  /** Display value when the KOL has no usable BD. */
  public static final String DISPLAY_PENDING_UPDATE = "PENDING_UPDATE";
}
