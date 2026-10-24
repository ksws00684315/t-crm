package com.tcrm.admin.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.order.service.KolAnalyticsOverviewDTO;
import com.tcrm.admin.order.service.KolAnalyticsService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KolAnalyticsControllerTest {
  private final KolAnalyticsService analytics = mock(KolAnalyticsService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc = KolMvcSupport.build(current, new KolAnalyticsController(analytics));

  @Test
  void shouldRequireKolToken() throws Exception {
    mvc.perform(get("/kol-api/analytics/overview")).andExpect(status().isUnauthorized());
    verifyNoInteractions(analytics);
  }

  @Test
  void shouldServeTheOverviewWithAmountsAsStrings() throws Exception {
    when(analytics.overview("2026-10-01T00:00:00+08:00", "2026-10-08T00:00:00+08:00"))
        .thenReturn(
            new KolAnalyticsOverviewDTO(
                Instant.parse("2026-10-08T03:00:00Z"),
                new BigDecimal("150.00"),
                3,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                List.of(
                    new KolAnalyticsOverviewDTO.Day(
                        "2026-10-01", new BigDecimal("100.50"), BigDecimal.ZERO.setScale(2)))));
    String token = KolMvcSupport.actAs(current, kol());

    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "2026-10-01T00:00:00+08:00")
                .param("to", "2026-10-08T00:00:00+08:00")
                .header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.salesCny").value("150.00"))
        .andExpect(jsonPath("$.data.paidOrderCount").value(3))
        .andExpect(jsonPath("$.data.commissionCny").value("0.00"))
        .andExpect(jsonPath("$.data.daily[0].date").value("2026-10-01"))
        .andExpect(jsonPath("$.data.daily[0].salesCny").value("100.50"))
        .andExpect(jsonPath("$.data.daily[0].commissionCny").value("0.00"))
        .andExpect(jsonPath("$.data.asOf").isNotEmpty());
  }

  @Test
  void shouldMapValidationFailuresToBadRequest() throws Exception {
    when(analytics.overview(any(), any()))
        .thenThrow(new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED));
    String token = KolMvcSupport.actAs(current, kol());
    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "bad")
                .param("to", "2026-10-08T00:00:00+08:00")
                .header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "2026-10-01T00:00:00+08:00")
                .header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        700L,
        "KOL0700",
        "Creator",
        "creator@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        77L,
        "Company",
        true);
  }
}
