package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.tcrm.admin.common.AuditSanitizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditSanitizerTest {
  private final AuditSanitizer sanitizer = new AuditSanitizer(JsonMapper.builder().build());

  @Test
  void redactsNestedSensitiveFieldsBeforeTruncation() {
    String value =
        sanitizer.sanitize(
            Map.of(
                "password",
                "sensitive-value",
                "nested",
                List.of(
                    Map.of(
                        "oldPassword",
                        "old-sensitive",
                        "Authorization",
                        "auth-sensitive",
                        "username",
                        "operator"))));
    assertFalse(value.contains("sensitive"));
    assertTrue(value.contains("[REDACTED]"));
    assertTrue(value.contains("operator"));
    assertEquals(2000, sanitizer.sanitize(Map.of("message", "a".repeat(3000))).length());
  }
}
