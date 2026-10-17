package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CopyTokenPlaintextsTest {
  private final CopyTokenPlaintexts plaintexts = new CopyTokenPlaintexts();

  @Test
  void shouldGenerateUniqueTokensInExpectedFormat() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      String token = plaintexts.generate();
      assertThat(CopyTokenPlaintexts.looksValid(token)).isTrue();
      assertThat(seen.add(token)).isTrue();
      String hint = CopyTokenPlaintexts.hint(token);
      assertThat(hint).startsWith("TKN-").contains("****").hasSize("TKN-".length() + 4 + 4 + 4);
      assertThat(CopyTokenPlaintexts.sha256Hex(token)).hasSize(64).matches("[0-9a-f]{64}");
    }
  }

  @Test
  void shouldRejectMalformedPlaintext() {
    assertThat(CopyTokenPlaintexts.looksValid(null)).isFalse();
    assertThat(CopyTokenPlaintexts.looksValid("")).isFalse();
    assertThat(CopyTokenPlaintexts.looksValid("TKN-SHORT")).isFalse();
    assertThat(CopyTokenPlaintexts.looksValid("TKN-" + "A".repeat(26).toLowerCase())).isFalse();
  }
}
