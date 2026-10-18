package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class KolPromotionSupportTest {
  @Test
  void shouldShowPendingUpdate_whenBdMissingOrUnusable() {
    assertThat(KolPromotionSupport.displayStatus("PROMOTING", null))
        .isEqualTo(PromotionStatus.DISPLAY_PENDING_UPDATE);
    assertThat(KolPromotionSupport.displayStatus("PROMOTING", new BdUserDTO(1L, "bd", "BD", false)))
        .isEqualTo(PromotionStatus.DISPLAY_PENDING_UPDATE);
    assertThat(KolPromotionSupport.displayStatus("PROMOTING", new BdUserDTO(1L, "bd", "BD", true)))
        .isEqualTo("PROMOTING");
  }

  @Test
  void shouldRequireAllPromotionFields_whenCheckingCompleteness() {
    KolDO kol = new KolDO();
    kol.setDefaultRate(new BigDecimal("0.1000"));
    kol.setLandingUrl("https://landing.example/");
    kol.setInviteCode("inv-1");
    assertThat(KolPromotionSupport.configComplete(kol, List.of())).isFalse();
    assertThat(KolPromotionSupport.configComplete(kol, List.of(1L))).isTrue();
    kol.setInviteCode(null);
    assertThat(KolPromotionSupport.configComplete(kol, List.of(1L))).isFalse();
  }
}
