package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The generated reset password (task 147) obeys the rules every password setter enforces. */
class RandomPasswordsTest {

  @Test
  void shouldUseTheDeduplicatedAlphabetAndLength() {
    for (int i = 0; i < 100; i++) {
      String password = RandomPasswords.next();
      assertThat(password)
          .hasSize(RandomPasswords.LENGTH)
          .matches("[" + RandomPasswords.ALPHABET + "]+");
    }
  }

  @Test
  void shouldSatisfyThePasswordRulesEverySetterEnforces() {
    // 10..72 printable ASCII characters (KolPasswordChangeRequest, CreateUserRequest).
    String password = RandomPasswords.next();
    assertThat(password.length()).isBetween(10, 72);
    assertThat(password).matches("[\\x20-\\x7E]+");
  }

  @Test
  void shouldNotRepeatWithinAHundredDraws() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      seen.add(RandomPasswords.next());
    }
    assertThat(seen).hasSize(100);
  }
}
