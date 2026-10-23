package com.tcrm.admin.notification.api;

/** Notification delivery entry point used by the commission outbox worker. */
public interface PrepayNotificationApi {
  void deliver(PrepayNotification event);
}
