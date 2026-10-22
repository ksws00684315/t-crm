package com.tcrm.admin.system.model;

/** Outcome of executing a plan, stored on the plan and shown in the notification. */
public enum DepartureResult {
  /** Every creator of the account moved to the heir. */
  TRANSFERRED,
  /** No heir was chosen, so the creators became unassigned (owned by the company). */
  UNASSIGNED,
  /** The heir no longer qualified at execution time; the creators were not moved. */
  HEIR_INVALID
}
