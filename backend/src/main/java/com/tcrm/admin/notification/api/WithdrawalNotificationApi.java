package com.tcrm.admin.notification.api;

/** Notification delivery entry point used by the withdrawal outbox worker. */
public interface WithdrawalNotificationApi {
  void deliver(WithdrawalNotification event);
}
