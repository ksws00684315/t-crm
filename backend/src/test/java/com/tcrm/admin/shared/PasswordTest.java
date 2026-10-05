package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordTest {
  @Test
  void shouldVerifiesHashAndRejectsWrongPassword_whenRequested() {
    var encoder = new BCryptPasswordEncoder(4);
    var raw = UUID.randomUUID().toString();
    String hash = encoder.encode(raw);
    assertThat(hash).isNotEqualTo(raw);
    assertThat(encoder.matches(raw, hash)).isTrue();
    assertThat(encoder.matches(UUID.randomUUID().toString(), hash)).isFalse();
  }
}
