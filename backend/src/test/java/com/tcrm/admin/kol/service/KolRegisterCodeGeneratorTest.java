package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KolRegisterCodeGeneratorTest {
  @Test
  void shouldDraw8CharactersWithoutAmbiguousGlyphs() {
    String code = KolRegisterCodeGenerator.next();
    assertThat(code).hasSize(KolRegisterCodeGenerator.LENGTH).matches("[2-9A-HJ-NP-Z]{8}");
  }

  @Test
  void normalize_uppercases() {
    assertThat(KolRegisterCodeGenerator.normalize("ab2cdefg")).isEqualTo("AB2CDEFG");
  }
}
