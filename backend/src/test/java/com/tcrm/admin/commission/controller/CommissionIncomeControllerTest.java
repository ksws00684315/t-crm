package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.CommissionIncomeService;
import com.tcrm.admin.commission.service.CommissionIncomeService.CommissionLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeDetail;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeFilter;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomePage;
import com.tcrm.admin.commission.service.CommissionIncomeService.IncomeRow;
import com.tcrm.admin.commission.service.CommissionIncomeService.KolMoney;
import com.tcrm.admin.commission.service.CommissionIncomeService.PaymentLine;
import com.tcrm.admin.commission.service.CommissionIncomeService.Summary;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class CommissionIncomeControllerTest {
  private static final String BASE = "/admin-api/commission/incomes";

  private final CommissionIncomeService service = mock(CommissionIncomeService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new CommissionIncomeController(service, current));

  @Test
  void shouldListIncomeRowsWithSummary() throws Exception {
    when(service.list(any(), any()))
        .thenReturn(
            new IncomePage(
                new PageResult<>(List.of(row()), 1, 1, 20),
                new Summary(new BigDecimal("300.00"), 1, 2, 3, 4)));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.items[0].id").value("77"))
        .andExpect(jsonPath("$.data.page.items[0].estimated").value("500.00"))
        .andExpect(jsonPath("$.data.page.items[0].unverified").value(2))
        .andExpect(jsonPath("$.data.summary.paid").value("300.00"))
        .andExpect(jsonPath("$.data.summary.creators").value(1));
  }

  @Test
  void shouldBindPaymentWindowAndType() throws Exception {
    when(service.list(any(), any()))
        .thenReturn(
            new IncomePage(
                new PageResult<>(List.of(), 0, 1, 20), new Summary(BigDecimal.ZERO, 0, 0, 0, 0)));
    mvc.perform(
            get(BASE)
                .param("paymentType", "PREPAY")
                .param("start", "2026-10-01T00:00:00Z")
                .param("end", "2026-11-01T00:00:00Z")
                .header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk());
    verify(service)
        .list(
            any(),
            eq(
                new IncomeFilter(
                    null,
                    "PREPAY",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-11-01T00:00:00Z"),
                    1,
                    20)));
  }

  @Test
  void shouldRejectInvalidPaymentTypeAndTime() throws Exception {
    mvc.perform(
            get(BASE).param("paymentType", "GIFT").header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(BASE).param("start", "yesterday").header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldReturnDetailWithLinesAndPayments() throws Exception {
    when(service.detail(any(), eq(77L), any()))
        .thenReturn(
            new IncomeDetail(
                row(),
                List.of(
                    new CommissionLine(
                        7001L,
                        "WD123",
                        9001L,
                        "https://img",
                        "100.00",
                        "0.1000",
                        3,
                        900L,
                        "10.00",
                        "-2.00",
                        "CONFIRMED",
                        Instant.parse("2026-10-10T08:00:00Z"),
                        "DEBIT",
                        "KOL",
                        77L,
                        "Kol A")),
                List.of(
                    new PaymentLine(
                        8001L,
                        "PP-1",
                        "PREPAY",
                        77L,
                        "300.00",
                        Instant.parse("2026-10-22T08:00:00Z"),
                        BigDecimal.ONE,
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"),
                        "300.00",
                        "CNY",
                        55L,
                        null))));
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.income.id").value("77"))
        .andExpect(jsonPath("$.data.lines[0].rate").value("0.1000"))
        .andExpect(jsonPath("$.data.lines[0].direction").value("DEBIT"))
        .andExpect(jsonPath("$.data.payments[0].sourceNo").value("PP-1"))
        .andExpect(jsonPath("$.data.payments[0].deducted").value("100.00"));
  }

  @Test
  void shouldRequireCommissionViewPermission() throws Exception {
    mvc.perform(
            get(BASE)
                .header(
                    AdminMvcSupport.TOKEN_HEADER,
                    AdminMvcSupport.actAs(
                        current,
                        9L,
                        com.tcrm.admin.security.api.Role.STAFF,
                        com.tcrm.admin.security.api.DataScope.ALL,
                        java.util.Set.of())))
        .andExpect(status().isForbidden());
  }

  private static IncomeRow row() {
    return new IncomeRow(
        77L,
        "KOL-T62",
        "Kol A",
        "kol@example.test",
        5L,
        "BD One",
        2,
        3,
        new KolMoney(
            new BigDecimal("500.00"),
            new BigDecimal("200.00"),
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            new BigDecimal("300.00"),
            new BigDecimal("50.00"),
            new BigDecimal("150.00"),
            new BigDecimal("300.00"),
            BigDecimal.ZERO,
            new BigDecimal("300.00"),
            Instant.parse("2026-10-22T08:00:00Z")),
        2);
  }

  private String token() {
    return AdminMvcSupport.actAs(
        current,
        5L,
        com.tcrm.admin.security.api.Role.STAFF,
        com.tcrm.admin.security.api.DataScope.ALL,
        java.util.Set.of("commission:view"));
  }
}
