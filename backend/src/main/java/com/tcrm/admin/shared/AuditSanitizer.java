package com.tcrm.admin.shared;

import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AuditSanitizer {
  private final ObjectMapper json;

  public AuditSanitizer(ObjectMapper json) {
    this.json = json;
  }

  public String sanitize(Object value) {
    try {
      JsonNode node = json.valueToTree(value);
      redact(node);
      return truncate(json.writeValueAsString(node), 2000);
    } catch (RuntimeException e) {
      return "[unavailable]";
    }
  }

  private void redact(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      for (String key : java.util.List.copyOf(obj.propertyNames())) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("password")
            || lower.contains("token")
            || lower.contains("secret")
            || lower.contains("authorization")
            || lower.contains("credential")) obj.put(key, "[REDACTED]");
        else redact(obj.get(key));
      }
    } else if (node.isArray()) {
      node.forEach(this::redact);
    }
  }

  public static String truncate(String value, int length) {
    return value == null ? null : value.substring(0, Math.min(value.length(), length));
  }
}
