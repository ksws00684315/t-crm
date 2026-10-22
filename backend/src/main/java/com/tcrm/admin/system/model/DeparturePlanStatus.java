package com.tcrm.admin.system.model;

/** Lifecycle of a departure plan. At most one {@code SCHEDULED} plan exists per account. */
public enum DeparturePlanStatus {
  SCHEDULED,
  DONE,
  CANCELLED
}
