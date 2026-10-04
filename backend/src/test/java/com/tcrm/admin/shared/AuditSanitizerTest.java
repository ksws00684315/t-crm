package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditSanitizerTest {
  private final AuditSanitizer sanitizer = new AuditSanitizer(JsonMapper.builder().build());

  @Test
  void shouldRedactNestedSecrets_whenSensitiveFieldsArePresent() {
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
    assertThat(value.contains("sensitive")).isFalse();
    assertThat(value.contains("[REDACTED]")).isTrue();
    assertThat(value.contains("operator")).isTrue();
  }

  @Test
  void shouldLimitAuditSize_whenPayloadIsLong() {
    assertThat(sanitizer.sanitize(Map.of("message", "a".repeat(3000)))).hasSize(2000);
    assertThat(AuditSanitizer.truncate(null, 10)).isNull();
  }

  @Test
  void shouldRedactEveryCredentialCategory_whenAlternateKeyNamesAreUsed() {
    assertThat(
            sanitizer.sanitize(
                Map.of(
                    "accessToken",
                    "unit-secret",
                    "clientSecret",
                    "unit-secret",
                    "credentials",
                    "unit-secret")))
        .doesNotContain("unit-secret");
  }

  @Test
  void shouldReturnSafePlaceholder_whenSerializationFails() {
    var json = org.mockito.Mockito.mock(tools.jackson.databind.ObjectMapper.class);
    org.mockito.Mockito.when(json.valueToTree(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalArgumentException("unit-failure"));
    assertThat(new AuditSanitizer(json).sanitize(new Object())).isEqualTo("[unavailable]");
  }
}
