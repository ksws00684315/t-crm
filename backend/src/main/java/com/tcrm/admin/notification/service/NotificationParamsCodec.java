package com.tcrm.admin.notification.service;

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
          case PAYOUT_ACCOUNT_SUBMITTED -> new PayoutParams(params.kolCode(), params.accountType());
          case ORDER_SYNC_FAILED -> new ShopParams(params.shopName());
          case BD_TRANSFER_CANCELLED -> new TransferParams(params.kolCode(), params.reason());
          case KOLS_UNASSIGNED -> new UnassignedParams(params.bdName(), params.count());
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
          text(node, "accountType"),
          text(node, "shopName"),
          text(node, "reason"),
          text(node, "bdName"),
          count);
    } catch (JacksonException ex) {
      return empty();
    }
  }

  private static NotificationParamsDTO empty() {
    return new NotificationParamsDTO(null, null, null, null, null, null);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isString()) {
      return null;
    }
    return value.asString();
  }

  private record PayoutParams(String kolCode, String accountType) {}

  private record ShopParams(String shopName) {}

  private record TransferParams(String kolCode, String reason) {}

  private record UnassignedParams(String bdName, Integer count) {}
}
