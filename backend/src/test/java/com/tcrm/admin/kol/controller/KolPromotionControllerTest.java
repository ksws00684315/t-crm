package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.service.KolCleanupService;
import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolPromotionService.PlatformInput;
import com.tcrm.admin.kol.service.KolService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class KolPromotionControllerTest {
  private final KolService kols = mock(KolService.class);
  private final KolPromotionService promotions = mock(KolPromotionService.class);
  private final KolCleanupService cleanup = mock(KolCleanupService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new KolController(kols, promotions, cleanup));

  private String token() {
    return AdminMvcSupport.actAs(
        current, 1L, Role.STAFF, DataScope.OWN, java.util.Set.of("kol:edit", "kol:view"));
  }

  @Test
  void shouldReturnPromotionConfig() throws Exception {
    when(promotions.getConfig(10L))
        .thenReturn(
            new KolPromotionService.PromotionConfigDTO(
                "0.1000",
                List.of(new PlatformInput(3L, "https://landing.example/")),
                "inv",
                null,
                3L,
                null));
    mvc.perform(
            get("/admin-api/kol/kols/10/promotion-config")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.defaultRate").value("0.1000"))
        .andExpect(jsonPath("$.data.platforms[0].templateId").value("3"));
  }

  @Test
  void shouldReturnPromotionRecords() throws Exception {
    when(promotions.pageRecords(10L, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new KolPromotionService.PromotionRecordDTO(
                        "STATUS",
                        Map.of("relationStatus", "PENDING_PROMOTION"),
                        Map.of("relationStatus", "PROMOTING"),
                        null,
                        "BD",
                        java.time.Instant.parse("2026-10-18T00:00:00Z"),
                        null,
                        null)),
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
  void shouldReviewKol() throws Exception {
    mvc.perform(
            post("/admin-api/kol/kols/10/review")
                .header(AdminMvcSupport.TOKEN_HEADER, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\"}"))
        .andExpect(status().isOk());
    verify(promotions).review(eq(10L), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void shouldAcceptHiddenOptionalPromotionFields() throws Exception {
    mvc.perform(
            put("/admin-api/kol/kols/10/promotion-config")
                .header(AdminMvcSupport.TOKEN_HEADER, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"defaultRate\":\"0.1000\",\"platforms\":[{\"templateId\":\"3\",\"address\":null}],\"inviteCode\":null,\"sheetUrl\":\"https://docs.google.com/spreadsheets/d/abc\",\"remark\":null}"))
        .andExpect(status().isOk());
    verify(promotions).updateConfig(eq(10L), org.mockito.ArgumentMatchers.any());
  }

  private String viewOnlyToken() {
    return AdminMvcSupport.actAs(
        current, 1L, Role.STAFF, DataScope.OWN, java.util.Set.of("kol:view"));
  }

  @Test
  void shouldAllowViewOnlyStaff_toReadPromotionConfigAndRecords() throws Exception {
    when(promotions.getConfig(10L))
        .thenReturn(
            new KolPromotionService.PromotionConfigDTO(
                "0.1000",
                List.of(new PlatformInput(3L, "https://landing.example/")),
                "inv",
                null,
                3L,
                null));
    when(promotions.pageRecords(10L, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new KolPromotionService.PromotionRecordDTO(
                        "STATUS",
                        Map.of("relationStatus", "PENDING_PROMOTION"),
                        Map.of("relationStatus", "PROMOTING"),
                        null,
                        "BD",
                        java.time.Instant.parse("2026-10-18T00:00:00Z"),
                        null,
                        null)),
                1,
                1,
                20));
    String token = viewOnlyToken();
    mvc.perform(
            get("/admin-api/kol/kols/10/promotion-config")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.defaultRate").value("0.1000"));
    mvc.perform(
            get("/admin-api/kol/kols/10/promotion-records")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].changeType").value("STATUS"));
  }

  @Test
  void shouldReturn403_whenViewOnlyStaffWritesPromotion() throws Exception {
    String token = viewOnlyToken();
    mvc.perform(
            put("/admin-api/kol/kols/10/promotion-config")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"defaultRate\":\"0.1000\",\"platforms\":[{\"templateId\":3,\"address\":\"https://x\"}],\"inviteCode\":\"inv\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin-api/kol/kols/10/review")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(promotions);
  }
}
