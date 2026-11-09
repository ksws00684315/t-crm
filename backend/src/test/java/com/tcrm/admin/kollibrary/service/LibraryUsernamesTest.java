package com.tcrm.admin.kollibrary.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Creator ID normalisation and duplicate identity (requirement 1.2.6 ②, task 143 §3.1.4). */
class LibraryUsernamesTest {
  @Test
  void shouldTreatAtSignSpacesAndCaseAsTheSameCreator() {
    assertThat(LibraryUsernames.normalize("@BeautyByAmy ")).isEqualTo("beautybyamy");
    assertThat(LibraryUsernames.normalize("BEAUTYBYAMY")).isEqualTo("beautybyamy");
    assertThat(LibraryUsernames.normalize(" beautybyamy")).isEqualTo("beautybyamy");
    assertThat(LibraryUsernames.normalize(" @Beauty.By_Amy ")).isEqualTo("beauty.by_amy");
  }

  @Test
  void shouldBeEmpty_whenOnlyAnAtSignOrNothingIsGiven() {
    assertThat(LibraryUsernames.normalize("@")).isEmpty();
    assertThat(LibraryUsernames.normalize("  @  ")).isEmpty();
    assertThat(LibraryUsernames.normalize("   ")).isEmpty();
    assertThat(LibraryUsernames.normalize(null)).isEmpty();
    assertThat(LibraryUsernames.valid(LibraryUsernames.normalize("@"))).isFalse();
  }

  @Test
  void shouldAcceptLettersDigitsDotAndUnderscoreUpTo64() {
    assertThat(LibraryUsernames.valid("abc_1.x")).isTrue();
    assertThat(LibraryUsernames.valid("a".repeat(64))).isTrue();
    assertThat(LibraryUsernames.valid("a".repeat(65))).isFalse();
    assertThat(LibraryUsernames.valid("bad name")).isFalse();
    assertThat(LibraryUsernames.valid("")).isFalse();
  }
}
