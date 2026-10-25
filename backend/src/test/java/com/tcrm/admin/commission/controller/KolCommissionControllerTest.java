package com.tcrm.admin.commission.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.commission.service.KolCommissionService;
import com.tcrm.admin.commission.service.KolCommissionService.FundRecord;
import com.tcrm.admin.commission.service.KolCommissionService.Income;
import com.tcrm.admin.commission.service.KolCommissionService.Overview;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KolCommissionControllerTest {
  private final KolCommissionService commissions = mock(KolCommissionService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc =
      KolMvcSupport.build(current, new KolCommissionController(commissions, current));

  @Test
  void shouldReturnOverviewFromLoginIdentity() throws Exception {
    when(commissions.overview(11L, 22L))
        .thenReturn(
            new Overview(
                new Income(
                    "500.00", "200.00", "100.00", "0", "300.00", "50.00", "150.00", "300.00",
                    "300.00", "0.00"),
                Instant.parse("2026-10-24T08:00:00Z")));
    mvc.perform(get("/kol-api/commissions/overview").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.income.estimated").value("500.00"))
        .andExpect(jsonPath("$.data.income.frozen").value("200.00"))
        .andExpect(jsonPath("$.data.income.available").value("100.00"))
        .andExpect(jsonPath("$.data.income.occupied").value("0"))
        .andExpect(jsonPath("$.data.income.cumulativePaid").value("300.00"))
        .andExpect(jsonPath("$.data.income.deducted").value("50.00"))
        .andExpect(jsonPath("$.data.income.pendingDeduction").value("150.00"))
        .andExpect(jsonPath("$.data.income.prepayPaid").value("300.00"))
        .andExpect(jsonPath("$.data.income.paid").value("300.00"))
        .andExpect(jsonPath("$.data.income.commissionPaid").value("0.00"))
        .andExpect(jsonPath("$.data.asOf").value("2026-10-24T08:00:00Z"));
  }

  @Test
  void shouldPageUnifiedFundRecords() throws Exception {
    when(commissions.fundRecords(11L, 22L, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new FundRecord(
                        8001L,
                        "PREPAY",
                        Instant.parse("2026-10-21T08:00:00Z"),
                        "PP20261021000001",
                        "300.00",
                        "CNY",
                        "ab***@example.test",
                        Instant.parse("2026-10-22T08:00:00Z"),
                        "PAID",
                        7001L)),
                1,
                1,
                20));
    mvc.perform(get("/kol-api/fund-records").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").isNumber())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.items[0].id").value("8001"))
        .andExpect(jsonPath("$.data.items[0].type").value("PREPAY"))
        .andExpect(jsonPath("$.data.items[0].appliedAt").value("2026-10-21T08:00:00Z"))
        .andExpect(jsonPath("$.data.items[0].applicationNo").value("PP20261021000001"))
        .andExpect(jsonPath("$.data.items[0].amount").value("300.00"))
        .andExpect(jsonPath("$.data.items[0].currency").value("CNY"))
        .andExpect(jsonPath("$.data.items[0].maskedRecipient").value("ab***@example.test"))
        .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-10-22T08:00:00Z"))
        .andExpect(jsonPath("$.data.items[0].publicStatus").value("PAID"))
        .andExpect(jsonPath("$.data.items[0].sourceId").value("7001"));
  }

  @Test
  void shouldRejectAnonymousAccess() throws Exception {
    mvc.perform(get("/kol-api/commissions/overview")).andExpect(status().isUnauthorized());
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        22L, "KOL-T62", "Kol A", "kol@example.test", "US", "hash", 1, 1, 0, null, 11L, "Co", true);
  }

  private String token() {
    return KolMvcSupport.actAs(current, kol());
  }
}
