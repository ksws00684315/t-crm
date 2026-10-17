package com.tcrm.admin.notification.service;

import com.tcrm.admin.notification.model.NotificationType;
import java.time.Instant;

/** One in-app message for its recipient. */
public record NotificationDTO(
    Long id,
    NotificationType type,
    NotificationParamsDTO params,
    String bizType,
    Long bizId,
    Instant readTime,
    Instant createTime) {}
