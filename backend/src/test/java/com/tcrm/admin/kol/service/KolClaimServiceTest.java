package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KolClaimServiceTest {
  @Test
  void shouldMaskEmail_keepingFirstLetterAndDomain() {
    assertThat(KolClaimService.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
    assertThat(KolClaimService.maskEmail("a@x.io")).isEqualTo("a***@x.io");
    assertThat(KolClaimService.maskEmail(null)).isEqualTo("***");
  }
}
