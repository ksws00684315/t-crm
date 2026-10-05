package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolMapper;
import org.junit.jupiter.api.Test;

class KolCodeGeneratorTest {
  private final KolMapper kols = mock(KolMapper.class);
  private final KolCodeGenerator generator = new KolCodeGenerator(kols);

  @Test
  void shouldPadSequenceNumber_whenCodeIsFree() {
    when(kols.nextCodeNumber()).thenReturn(7L);
    assertThat(generator.next()).isEqualTo("KOL0007");
  }

  @Test
  void shouldWidenBeyondFourDigits_whenSequencePasses9999() {
    when(kols.nextCodeNumber()).thenReturn(10_000L);
    assertThat(generator.next()).isEqualTo("KOL10000");
  }

  @Test
  void shouldSkipTakenCodes_whenImportedCodesOccupySequenceNumbers() {
    when(kols.nextCodeNumber()).thenReturn(1L, 2L, 3L);
    when(kols.existsCode("KOL0001")).thenReturn(true);
    when(kols.existsCode("KOL0002")).thenReturn(true);
    assertThat(generator.next()).isEqualTo("KOL0003");
  }

  @Test
  void shouldFail_whenEveryCodeIsTaken() {
    when(kols.nextCodeNumber()).thenReturn(1L);
    when(kols.existsCode(anyString())).thenReturn(true);
    assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class);
  }
}
