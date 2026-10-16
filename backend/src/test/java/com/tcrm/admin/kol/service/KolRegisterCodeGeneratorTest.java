package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KolRegisterCodeGeneratorTest {
  @Test
  void shouldDraw32UnbiasedBase62Characters() {
    String code = KolRegisterCodeGenerator.next();
    assertThat(code).hasSize(KolRegisterCodeGenerator.LENGTH).matches("[0-9A-Za-z]{32}");
  }
}
