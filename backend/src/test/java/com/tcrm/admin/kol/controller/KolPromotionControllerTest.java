package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.kol.service.KolClaimService;
import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class KolPromotionControllerTest {
  private final KolService kols = mock(KolService.class);
  private final KolPromotionService promotions = mock(KolPromotionService.class);
  private final KolClaimService claims = mock(KolClaimService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new KolController(kols, promotions, claims));
  private final MockMvc ops =
      AdminMvcSupport.build(
          current,
          new KolOpsController(claims, mock(com.tcrm.admin.kol.service.KolCleanupService.class)));

  private String token() {
    return AdminMvcSupport.actAs(
        current, 1L, Role.STAFF, DataScope.OWN, java.util.Set.of("kol:edit", "kol:view"));
  }

  @Test
  void shouldChangePromotionStatus() throws Exception {
    mvc.perform(
            put("/admin-api/kol/kols/10/promotion-status")
                .header(AdminMvcSupport.TOKEN_HEADER, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PENDING_PROMOTION\"}"))
        .andExpect(status().isOk());
    verify(promotions).updateStatus(10L, PromotionStatus.PENDING_PROMOTION);
  }

  @Test
  void shouldReturn422_whenPromotionConfigIncomplete() throws Exception {
    org.mockito.Mockito.doThrow(
            new BusinessException(
                ErrorCode.KOL_PROMOTION_CONFIG_INCOMPLETE, List.of("landingUrl", "inviteCode")))
        .when(promotions)
        .updateStatus(eq(10L), eq(PromotionStatus.PROMOTING));
    mvc.perform(
            put("/admin-api/kol/kols/10/promotion-status")
                .header(AdminMvcSupport.TOKEN_HEADER, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PROMOTING\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("KOL_PROMOTION_CONFIG_INCOMPLETE"))
        .andExpect(jsonPath("$.data[0]").value("landingUrl"))
        .andExpect(jsonPath("$.data[1]").value("inviteCode"));
  }

  @Test
  void shouldReturnPromotionConfig() throws Exception {
    when(promotions.getConfig(10L))
        .thenReturn(
            new KolPromotionService.PromotionConfigDTO(
                "0.1000", List.of(3L), "https://landing.example/", "inv", null, 3L, null));
    mvc.perform(
            get("/admin-api/kol/kols/10/promotion-config")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.defaultRate").value("0.1000"))
        .andExpect(jsonPath("$.data.platformTemplateIds[0]").value("3"));
  }

  @Test
  void shouldReturnPromotionRecords() throws Exception {
    when(promotions.pageRecords(10L, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new KolPromotionService.PromotionRecordDTO(
                        "STATUS",
                        Map.of("promotionStatus", "PENDING_REVIEW"),
                        Map.of("promotionStatus", "PENDING_PROMOTION"),
                        null,
                        "bd",
                        java.time.Instant.parse("2026-10-18T00:00:00Z"))),
                1,
                1,
                20));
    mvc.perform(
            get("/admin-api/kol/kols/10/promotion-records")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].changeType").value("STATUS"));
  }

  @Test
  void shouldClaimKol() throws Exception {
    mvc.perform(post("/admin-api/kol/kols/10/claim").header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk());
    verify(claims).claim(10L);
  }

  @Test
  void shouldSearchClaimable() throws Exception {
    when(claims.findClaimable("KOL-AAA001"))
        .thenReturn(
            new KolClaimService.ClaimableKolDTO(
                10L, "KOL-AAA001", "Alice", "a***@example.com", "PENDING_UPDATE"));
    ops.perform(
            get("/admin-api/kol/claimable")
                .param("keyword", "KOL-AAA001")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kolCode").value("KOL-AAA001"))
        .andExpect(jsonPath("$.data.maskedEmail").value("a***@example.com"));
  }
}
