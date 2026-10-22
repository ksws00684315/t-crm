package com.tcrm.admin.notification.service;

/** Render parameters. Only the fields for the message type are stored; the rest stay null. */
public record NotificationParamsDTO(
    String kolCode,
    String accountType,
    String shopName,
    String reason,
    String bdName,
    Integer count,
    String prepayNo) {}
