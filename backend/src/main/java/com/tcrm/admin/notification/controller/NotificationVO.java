package com.tcrm.admin.notification.controller;

import com.tcrm.admin.notification.model.NotificationType;
import com.tcrm.admin.notification.service.NotificationDTO;
import com.tcrm.admin.notification.service.NotificationParamsDTO;
import java.time.Instant;

public record NotificationVO(
    Long id,
    NotificationType type,
    NotificationParamsDTO params,
    String bizType,
    Long bizId,
    Instant readTime,
    Instant createTime) {
  public static NotificationVO from(NotificationDTO row) {
    return new NotificationVO(
        row.id(),
        row.type(),
        row.params(),
        row.bizType(),
        row.bizId(),
        row.readTime(),
        row.createTime());
  }
}
