package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolMapper;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class KolCodeGeneratorTest {
  private final KolMapper kols = mock(KolMapper.class);

  /** Always returns index 0 → letter 'A'. */
  private final SecureRandom fixed =
      new SecureRandom() {
        @Override
        public int nextInt(int bound) {
          return 0;
        }
      };

  private final KolCodeGenerator generator = new KolCodeGenerator(kols, fixed);

  @Test
  void shouldPadSequenceNumber_whenCodeIsFree() {
    when(kols.nextCodeNumber()).thenReturn(7L);
    assertThat(generator.next()).isEqualTo("KOL-AAA007");
  }

  @Test
  void shouldWidenBeyondThreeDigits_whenSequencePasses999() {
    when(kols.nextCodeNumber()).thenReturn(1_000L);
    assertThat(generator.next()).isEqualTo("KOL-AAA1000");
  }

  @Test
  void shouldSkipTakenCodes_whenImportedCodesOccupySequenceNumbers() {
    when(kols.nextCodeNumber()).thenReturn(1L, 2L, 3L);
    when(kols.existsCode("KOL-AAA001")).thenReturn(true);
    when(kols.existsCode("KOL-AAA002")).thenReturn(true);
    assertThat(generator.next()).isEqualTo("KOL-AAA003");
  }

  @Test
  void shouldFail_whenEveryCodeIsTaken() {
    when(kols.nextCodeNumber()).thenReturn(1L);
    when(kols.existsCode(anyString())).thenReturn(true);
    assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class);
  }
}
