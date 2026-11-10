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

  @Test
  void shouldRedactTheCreatorLibraryContact_asPersonalData() {
    AuditSanitizer sanitizer = new AuditSanitizer(JsonMapper.builder().build());

    String sanitized =
        sanitizer.sanitize(
            new LibraryProfile("beautybyamy", "+1 555 0100", "Amy BD", "met at expo"));

    assertThat(sanitized)
        .contains("beautybyamy")
        .contains("followUpOwner")
        .contains("[REDACTED]")
        .doesNotContain("555 0100")
        .doesNotContain("met at expo");
  }

  private record CredentialRequest(String appKey, String appSecret) {}

  private record LibraryProfile(
      String kolUsername, String contact, String followUpOwner, String followUpRemark) {}
}
