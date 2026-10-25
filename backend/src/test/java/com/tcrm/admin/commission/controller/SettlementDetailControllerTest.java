package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.DetailFilter;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.Line;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.SettlementRow;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class SettlementDetailControllerTest {
  private final CommissionSettlementDetailService details =
      mock(CommissionSettlementDetailService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new SettlementDetailController(details, current));

  @Test
  void shouldListPerOrderClearingRows() throws Exception {
    when(details.page(any(), any(DetailFilter.class)))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new SettlementRow(
                        5001L,
                        "WD-1001",
                        "WEIDIAN",
                        "Shop A",
                        List.of(77L),
                        List.of("Kol A"),
                        "200.00",
                        "20.00",
                        "180.00",
                        "18.00",
                        List.of("0.1000"),
                        "162.00",
                        "POSTED",
                        Instant.parse("2026-10-10T08:00:00Z"),
                        List.of(
                            new Line(
                                7001L,
                                "WD-1001",
                                9001L,
                                null,
                                "200.00",
                                "0.1000",
                                3,
                                900L,
                                "18.00",
                                "0.00",
                                "CONFIRMED",
                                Instant.parse("2026-10-10T08:00:00Z"),
                                "CREDIT",
                                "KOL",
                                77L,
                                "Kol A")))),
                1,
                1,
                20));
    mvc.perform(
            get("/admin-api/commission/settlement-details")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].orderNo").value("WD-1001"))
        .andExpect(jsonPath("$.data.items[0].retained").value("162.00"))
        .andExpect(jsonPath("$.data.items[0].status").value("POSTED"))
        .andExpect(jsonPath("$.data.items[0].lines[0].commission").value("18.00"));
  }

  @Test
  void shouldHideRetainedAndMarkUnverifiedWhenAnyLineIsUnverified() throws Exception {
    when(details.page(any(), any(DetailFilter.class)))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new SettlementRow(
                        5002L,
                        "WD-1002",
                        "WEIDIAN",
                        null,
                        List.of(),
                        List.of(),
                        "100.00",
                        "0.00",
                        "100.00",
                        "0.00",
                        List.of(),
                        null,
                        "UNVERIFIED",
                        null,
                        List.of())),
                1,
                1,
                20));
    mvc.perform(
            get("/admin-api/commission/settlement-details")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].retained").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].status").value("UNVERIFIED"));
  }

  @Test
  void shouldAcceptOnlyWeidianChannel() throws Exception {
    when(details.page(any(), any(DetailFilter.class)))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(
            get("/admin-api/commission/settlement-details")
                .param("channel", "TAOBAO")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/admin-api/commission/settlement-details")
                .param("channel", "WEIDIAN")
                .param("orderNo", "WD-1001")
                .header(AdminMvcSupport.TOKEN_HEADER, viewerToken()))
        .andExpect(status().isOk());
  }

  private String viewerToken() {
    return AdminMvcSupport.actAs(
        current,
        9L,
        com.tcrm.admin.security.api.Role.STAFF,
        com.tcrm.admin.security.api.DataScope.ALL,
        java.util.Set.of("commission:view"));
  }
}
