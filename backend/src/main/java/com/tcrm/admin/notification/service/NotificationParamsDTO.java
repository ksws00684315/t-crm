package com.tcrm.admin.notification.service;

/** Render parameters. Only the fields for the message type are stored; the rest stay null. */
public record NotificationParamsDTO(
    String kolCode,
    String accountType,
    String shopName,
    String reason,
    String bdName,
    Integer count,
    /** Departure messages: display name of the heir, null when none was named. */
    String heirName,
    /** Departure messages: effective time as an ISO 8601 instant, formatted by the admin UI. */
    String effectiveAt,
    /** Departure messages: TRANSFERRED, UNASSIGNED or HEIR_INVALID. */
    String result,
    /** Departure messages: RESIGN or DISABLE. */
    String planType) {

  /** Message kinds that carry none of the departure fields. */
  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count) {
    this(kolCode, accountType, shopName, reason, bdName, count, null, null, null, null);
  }
}
