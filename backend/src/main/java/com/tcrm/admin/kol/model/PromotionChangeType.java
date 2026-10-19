package com.tcrm.admin.kol.model;

/** Append-only promotion record kind (ADR-025). */
public enum PromotionChangeType {
  CONFIG,
  STATUS,
  REVIEW,
  AUTO_CLEANUP
}
