package com.tcrm.admin.notification.service;

/** Display parameters for account, prepay, withdrawal and departure notifications. */
public record NotificationParamsDTO(
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
    String planType,
    String applicationNo) {
  public NotificationParamsDTO(
      String kolCode,
      String accountType,
      String shopName,
      String reason,
      String bdName,
      Integer count) {
    this(kolCode, accountType, shopName, reason, bdName, count, null, null, null, null, null, null);
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
