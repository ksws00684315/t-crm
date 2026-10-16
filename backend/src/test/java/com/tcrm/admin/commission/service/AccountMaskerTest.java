package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountMaskerTest {
  @ParameterizedTest
  @CsvSource({
    "alice.pay@example.com, al***@example.com",
    "ab@gmail.com, ***@gmail.com",
    "a@x.io, ***@x.io",
    "weird@@nope, weir…nope"
  })
  void shouldMaskEmails_byLocalPrefixAndDomain(String input, String expected) {
    assertThat(AccountMasker.mask(input)).isEqualTo(expected);
  }

  @Test
  void shouldMaskLongStrings_withFirstAndLastFour() {
    assertThat(AccountMasker.mask("TQ5xnPW9tZ3kA7qL9aZ")).isEqualTo("TQ5x…L9aZ");
  }

  @Test
  void shouldMaskShortStrings_firstAndLastOneOnly() {
    assertThat(AccountMasker.mask("Ab3xY9kQ")).isEqualTo("A…Q");
    assertThat(AccountMasker.mask("1234567")).isEqualTo("1…7");
    assertThat(AccountMasker.mask("abc")).isEqualTo("a…c");
  }

  @Test
  void shouldMaskTinyStrings_firstAndLastCharacterOnly() {
    assertThat(AccountMasker.mask("a")).isEqualTo("a…a");
    assertThat(AccountMasker.mask("ab")).isEqualTo("a…b");
    assertThat(AccountMasker.mask("12345678")).isEqualTo("1…8");
  }

  @Test
  void shouldPassThroughBlank() {
    assertThat(AccountMasker.mask(null)).isNull();
    assertThat(AccountMasker.mask("")).isEmpty();
  }
}
