package com.tcrm.redirect.service;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One active entry of the Redis link index (design §7.4). The value is a JSON object {@code
 * {"kolItemId":"1","targetUrl":"https://...","status":1}}; any other value is rejected.
 */
record LinkEntry(String kolItemId, String targetUrl) {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID_PATTERN = "[1-9][0-9]{0,18}";

  /** Returns the entry when the value is well formed and active; empty otherwise. */
  static Optional<LinkEntry> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (JacksonException e) {
      return Optional.empty();
    }
    JsonNode id = node.path("kolItemId");
    JsonNode url = node.path("targetUrl");
    JsonNode status = node.path("status");
    boolean scalarId = id.isString() || id.isIntegralNumber();
    boolean active =
        status.isIntegralNumber() ? status.asInt() == 1 : "1".equals(status.asString(""));
    if (!node.isObject() || !scalarId || !url.isString() || !active) {
      return Optional.empty();
    }
    return of(id.asString(), url.asString());
  }

  private static Optional<LinkEntry> of(String id, String url) {
    return id.matches(ID_PATTERN) ? Optional.of(new LinkEntry(id, url)) : Optional.empty();
  }
}
