package com.tcrm.admin.kol.model;

/**
 * Derived login status (ADR-025). Not stored. List filter only supports {@code FROZEN} and {@code
 * NORMAL_OR_ABNORMAL}.
 */
public enum LoginStatus {
  NORMAL,
  FROZEN,
  ABNORMAL;

  /** Filter value: account enabled (status=1); ABNORMAL vs NORMAL needs Redis. */
  public static final String FILTER_NORMAL_OR_ABNORMAL = "NORMAL_OR_ABNORMAL";
}
