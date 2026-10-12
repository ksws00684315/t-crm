package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditSanitizerTest {
  @Test
  void shouldRedactAppSecretInAuditPayload() {
    AuditSanitizer sanitizer = new AuditSanitizer(JsonMapper.builder().build());

    String sanitized = sanitizer.sanitize(new CredentialRequest("app-key", "private-secret"));

    assertThat(sanitized)
        .contains("appKey")
        .contains("[REDACTED]")
        .doesNotContain("private-secret");
  }

  private record CredentialRequest(String appKey, String appSecret) {}
}
