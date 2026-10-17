package com.tcrm.admin.kol.model;

import java.time.Instant;

/**
 * Derived display status for a copy token (task 17a §4.1). Judgment order: REVOKED, EXPIRED,
 * EXHAUSTED, then ACTIVE.
 */
public enum CopyTokenDisplayStatus {
  ACTIVE,
  EXHAUSTED,
  EXPIRED,
  REVOKED;

  public static CopyTokenDisplayStatus of(String status, Instant expiresAt, int used, int quota) {
    if (KolCopyTokenDO.STATUS_REVOKED.equals(status)) {
      return REVOKED;
    }
    Instant now = Instant.now();
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      return EXPIRED;
    }
    if (used >= quota) {
      return EXHAUSTED;
    }
    return ACTIVE;
  }

  /** Same derivation with an explicit clock (tests). */
  public static CopyTokenDisplayStatus of(
      String status, Instant expiresAt, int used, int quota, Instant now) {
    if (KolCopyTokenDO.STATUS_REVOKED.equals(status)) {
      return REVOKED;
    }
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      return EXPIRED;
    }
    if (used >= quota) {
      return EXHAUSTED;
    }
    return ACTIVE;
  }
}
