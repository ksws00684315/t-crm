package com.tcrm.admin.integration.service;

import tools.jackson.databind.JsonNode;

final class WeidianResponseParser {
  private WeidianResponseParser() {}

  static int statusCode(JsonNode envelope) {
    return envelope.path("status").path("status_code").asInt(-1);
  }

  static String statusReason(JsonNode envelope) {
    JsonNode reason = envelope.path("status").path("status_reason");
    return reason.isMissingNode() || reason.isNull() ? "" : reason.asText("");
  }

  static JsonNode resultOrThrow(JsonNode envelope) {
    int code = statusCode(envelope);
    if (code != 0) {
      throw new WeidianApiException(code, "Weidian API error " + code, isRetryable(code));
    }
    JsonNode result = envelope.get("result");
    if (result == null || result.isNull()) {
      throw new WeidianApiException(-1, "Weidian API response is missing result", false);
    }
    return result;
  }

  static boolean isRetryable(int code) {
    return code == 10000 || code == 10001 || code == 10002 || code == 10004 || code == 10005;
  }
}
