package com.tcrm.admin.link.model;

/** Why a current link is not usable (task 06 §4.3 status rule, §5 link list). */
public enum LinkUnavailableReason {
  /** The KOL is disabled. */
  KOL_DISABLED,
  /** The original product is off sale. */
  PRODUCT_OFF_SHELF,
  /** The shop of the exclusive item is disabled. */
  SHOP_DISABLED
}
