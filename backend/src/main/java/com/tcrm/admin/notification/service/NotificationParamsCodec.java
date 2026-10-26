package com.tcrm.admin.notification.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcrm.admin.notification.model.NotificationType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stores only the fields of one message type. Extra or missing fields on read are ignored. */
final class NotificationParamsCodec {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private NotificationParamsCodec() {}

  static String write(NotificationType type, NotificationParamsDTO params) {
    Object body =
        switch (type) {
          case PAYOUT_ACCOUNT_SUBMITTED ->
              new PayoutParams(params.kolCode(), params.kolName(), params.accountType());
          case ORDER_SYNC_FAILED -> new ShopParams(params.shopName());
          case BD_TRANSFER_CANCELLED ->
              new TransferParams(params.kolCode(), params.kolName(), params.reason());
          case KOLS_UNASSIGNED -> new UnassignedParams(params.bdName(), params.count());
          case PREPAY_SUBMITTED, PREPAY_APPROVED, PREPAY_REJECTED, PREPAY_PAID ->
              new PrepayParams(params.prepayNo());
          case DEPARTURE_PLAN_SUBMITTED ->
              new DepartureParams(params.bdName(), params.effectiveAt());
          case DEPARTURE_PLAN_EXECUTED ->
              new DepartureExecutedParams(
                  params.bdName(),
                  params.effectiveAt(),
                  params.heirName(),
                  params.result(),
                  params.planType());
          case WITHDRAWAL_SUBMITTED, WITHDRAWAL_APPROVED, WITHDRAWAL_REJECTED, WITHDRAWAL_PAID ->
              new WithdrawalParams(params.kolCode(), params.kolName(), params.applicationNo());
        };
    return JSON.writeValueAsString(body);
  }

  static NotificationParamsDTO read(String json) {
    if (json == null || json.isBlank()) {
      return empty();
    }
    try {
      JsonNode node = JSON.readTree(json);
      Integer count = node.path("count").isNumber() ? node.path("count").intValue() : null;
      return new NotificationParamsDTO(
          text(node, "kolCode"),
          text(node, "kolName"),
          text(node, "accountType"),
          text(node, "shopName"),
          text(node, "reason"),
          text(node, "bdName"),
          count,
          text(node, "prepayNo"),
          text(node, "heirName"),
          text(node, "effectiveAt"),
          text(node, "result"),
          text(node, "planType"),
          text(node, "applicationNo"));
    } catch (JacksonException ex) {
      return empty();
    }
  }

  private static NotificationParamsDTO empty() {
    return new NotificationParamsDTO(null, null, null, null, null, null, null);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isString()) {
      return null;
    }
    return value.asString();
  }

  /**
   * {@code kolName} is absent rather than null when a message is written without it (KOL no longer
   * resolvable), so those rows keep the exact legacy shape written before the field existed.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record PayoutParams(String kolCode, String kolName, String accountType) {}

  private record ShopParams(String shopName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record TransferParams(String kolCode, String kolName, String reason) {}

  private record UnassignedParams(String bdName, Integer count) {}

  private record PrepayParams(String prepayNo) {}

  private record DepartureParams(String bdName, String effectiveAt) {}

  private record DepartureExecutedParams(
      String bdName, String effectiveAt, String heirName, String result, String planType) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record WithdrawalParams(String kolCode, String kolName, String applicationNo) {}
}
