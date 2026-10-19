package com.tcrm.admin.kol.model;

/** Stored relation status (ADR-025). PENDING_UPDATE is display-only. */
public enum RelationStatus {
  PENDING_PROMOTION,
  PROMOTING,
  INVALID;

  /** Display value when the KOL has no usable BD (and is not INVALID). */
  public static final String DISPLAY_PENDING_UPDATE = "PENDING_UPDATE";
}
