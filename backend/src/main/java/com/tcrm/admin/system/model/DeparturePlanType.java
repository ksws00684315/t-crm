package com.tcrm.admin.system.model;

/** What a departure plan does to the account when it takes effect. */
public enum DeparturePlanType {
  /** Resignation: the account stops working for the company. */
  RESIGN,
  /** Disable: the account is switched off, without the wording of a resignation. */
  DISABLE
}
