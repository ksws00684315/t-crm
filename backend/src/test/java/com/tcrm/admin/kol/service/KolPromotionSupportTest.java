package com.tcrm.admin.kol.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.kol.service.KolPromotionSupport.PlatformRef;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class KolPromotionSupportTest {
  @Test
  void displayRelationStatus_freezeThenMissingBdThenStoredStatus() {
    BdUserDTO unusable = new BdUserDTO(1L, "bd", "BD", false);
    assertEquals(
        RelationStatus.INVALID.name(),
        KolPromotionSupport.displayRelationStatus(0, RelationStatus.PROMOTING.name(), null));
    assertEquals(
        RelationStatus.DISPLAY_PENDING_UPDATE,
        KolPromotionSupport.displayRelationStatus(1, RelationStatus.PROMOTING.name(), unusable));
    assertEquals(
        RelationStatus.DISPLAY_PENDING_UPDATE,
        KolPromotionSupport.displayRelationStatus(1, RelationStatus.PROMOTING.name(), null));
    BdUserDTO usable = new BdUserDTO(1L, "bd", "BD", true);
    assertEquals(
        RelationStatus.PROMOTING.name(),
        KolPromotionSupport.displayRelationStatus(1, RelationStatus.PROMOTING.name(), usable));
  }

  @Test
  void missingConfigFields_listsFixedNames() {
    KolDO kol = new KolDO();
    kol.setReviewStatus(ReviewStatus.APPROVED.name());
    List<String> missing = KolPromotionSupport.missingConfigFields(kol, List.of());
    assertTrue(missing.contains("defaultRate"));
    assertTrue(missing.contains("inviteCode"));
    assertTrue(missing.contains("platforms"));
    assertTrue(missing.contains("sheetUrl"));
    assertTrue(missing.contains("remark"));
    assertFalse(missing.contains("review"));
  }

  @Test
  void missingConfigFields_requiresReviewAndAddresses() {
    KolDO kol = new KolDO();
    kol.setReviewStatus(ReviewStatus.PENDING.name());
    kol.setDefaultRate(new BigDecimal("0.1000"));
    kol.setInviteCode("ABC");
    kol.setLinkTemplateId(7L);
    kol.setSheetUrl("https://docs.google.com/x");
    List<PlatformRef> platforms = List.of(new PlatformRef(7L, null));
    List<String> missing = KolPromotionSupport.missingConfigFields(kol, platforms);
    assertTrue(missing.contains("review"));
    assertTrue(missing.contains("platformAddress"));
  }

  @Test
  void configComplete_whenAllPresent() {
    KolDO kol = new KolDO();
    kol.setReviewStatus(ReviewStatus.APPROVED.name());
    kol.setDefaultRate(new BigDecimal("0.1550"));
    kol.setInviteCode("INV");
    kol.setLinkTemplateId(7L);
    kol.setSheetUrl("https://docs.google.com/sheet");
    kol.setPromotionRemark("Reviewed");
    assertTrue(
        KolPromotionSupport.configComplete(
            kol, List.of(new PlatformRef(7L, "https://example.com/x"))));
  }
}
