package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Permission codes per company edition (ADR-038 §2.4, task 143 §2.3.4): the distribution edition
 * keeps every code it had before, the creator-library edition has exactly two.
 */
class PermissionEditionTest {
  /** The 22 codes that existed before ADR-038; they belong to the distribution product. */
  private static final List<String> DISTRIBUTION_CODES =
      List.of(
          "kol:view",
          "kol:edit",
          "kol:register-link",
          "kol:copy-token",
          "product:view",
          "product:edit",
          "product:status",
          "order:view",
          "order:manage",
          "commission:view",
          "commission:freeze",
          "payout-account:review",
          "withdrawal:review",
          "withdrawal:pay",
          "prepay:apply",
          "prepay:review",
          "prepay:finance-review",
          "prepay:pay",
          "dashboard:shop",
          "dashboard:team",
          "dashboard:kol",
          "data:export");

  @Test
  void shouldKeepEveryExistingCodeInDistribution() {
    assertThat(Permission.codesOf(Edition.DISTRIBUTION)).isEqualTo(DISTRIBUTION_CODES);
  }

  @Test
  void shouldOfferOnlyTheTwoLibraryCodesInKolLibrary() {
    assertThat(Permission.codesOf(Edition.KOL_LIBRARY))
        .containsExactly("kol-library:view", "kol-library:edit");
    assertThat(Permission.KOL_LIBRARY_VIEW.edition()).isEqualTo(Edition.KOL_LIBRARY);
    assertThat(Permission.KOL_LIBRARY_VIEW.group()).isEqualTo(Permission.Group.KOL_LIBRARY);
    assertThat(Permission.KOL_VIEW.edition()).isEqualTo(Edition.DISTRIBUTION);
  }

  @Test
  void shouldSplitTheRegistryAndLeaveNoCodeUnowned() {
    assertThat(Permission.codesOf(Edition.DISTRIBUTION))
        .doesNotContainAnyElementsOf(Permission.codesOf(Edition.KOL_LIBRARY));
    assertThat(Permission.codesOf(Edition.DISTRIBUTION).size() + 2)
        .isEqualTo(Permission.values().length);
  }
}
