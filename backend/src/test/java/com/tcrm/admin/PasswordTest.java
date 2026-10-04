package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordTest {
  @Test
  void verifiesHashAndRejectsWrongPassword() {
    var encoder = new BCryptPasswordEncoder(4);
    var raw = UUID.randomUUID().toString();
    String hash = encoder.encode(raw);
    assertNotEquals(raw, hash);
    assertTrue(encoder.matches(raw, hash));
    assertFalse(encoder.matches(UUID.randomUUID().toString(), hash));
  }
}
