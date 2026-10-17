package com.tcrm.admin.notification.model;

/** In-app message kinds this task writes. The name is stored and rendered by the admin UI. */
public enum NotificationType {
  PAYOUT_ACCOUNT_SUBMITTED,
  ORDER_SYNC_FAILED,
  BD_TRANSFER_CANCELLED,
  KOLS_UNASSIGNED
}
