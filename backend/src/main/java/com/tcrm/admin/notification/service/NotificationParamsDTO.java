package com.tcrm.admin.notification.service;

/**
 * Display parameters for account, prepay, withdrawal and departure notifications.
 *
 * @param kolName KOL display name next to {@code kolCode} (task 71, handoff B06); null for messages
 *     without KOL identity, for history written before the field existed, and when the KOL no
 *     longer resolves at write time
 */
public record NotificationParamsDTO(
    String kolCode,
    String kolName,
    String accountType,
    String shopName,
    String reason,
    String bdName,
    Integer count,
    String prepayNo,
    String heirName,
    String effectiveAt,
    String result,
    String planType,
    String applicationNo) {
  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count) {
    this(
        kolCode,
        null,
        accountType,
        shopName,
        reason,
        bdName,
        count,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count,
      String prepayNo) {
    this(
        kolCode,
        null,
        accountType,
        shopName,
        reason,
        bdName,
        count,
        prepayNo,
        null,
        null,
        null,
        null,
        null);
  }

  /** KOL-identity messages (task 71, handoff B06): code and name together. */
  public NotificationParamsDTO(
      String kolCode,
      String kolName,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count,
      String prepayNo) {
    this(
        kolCode,
        kolName,
        accountType,
        shopName,
        reason,
        bdName,
        count,
        prepayNo,
        null,
        null,
        null,
        null,
        null);
  }

  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count,
      String heirName,
      String effectiveAt,
      String result,
      String planType) {
    this(
        kolCode,
        null,
        accountType,
        shopName,
        reason,
        bdName,
        count,
        null,
        heirName,
        effectiveAt,
        result,
        planType,
        null);
  }

  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count,
      String prepayNo,
      String heirName,
      String effectiveAt,
      String result,
      String planType) {
    this(
        kolCode,
        null,
        accountType,
        shopName,
        reason,
        bdName,
        count,
        prepayNo,
        heirName,
        effectiveAt,
        result,
        planType,
        null);
  }
}
