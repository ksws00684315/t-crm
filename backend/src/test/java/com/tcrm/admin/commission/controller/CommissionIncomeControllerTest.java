package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
                new Summary(
                    new BigDecimal("300.00"),
                    new BigDecimal("20.00"),
                    new BigDecimal("280.00"),
                    1,
                    2,
                    3,
                    4)));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.items").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].id").isString())
        .andExpect(jsonPath("$.data.items[0].id").value("77"))
        .andExpect(jsonPath("$.data.items[0].name").value("Kol A"))
        .andExpect(jsonPath("$.data.items[0].code").value("KOL-T62"))
        .andExpect(jsonPath("$.data.items[0].email").value("kol@example.test"))
        .andExpect(jsonPath("$.data.items[0].bdId").value("5"))
        .andExpect(jsonPath("$.data.items[0].bd").value("BD One"))
        .andExpect(jsonPath("$.data.items[0].orderCount").isNumber())
        .andExpect(jsonPath("$.data.items[0].orderCount").value(2))
        .andExpect(jsonPath("$.data.items[0].quantity").isNumber())
        .andExpect(jsonPath("$.data.items[0].quantity").value(3))
        .andExpect(jsonPath("$.data.items[0].estimated").value("500.00"))
        .andExpect(jsonPath("$.data.items[0].frozen").value("200.00"))
        .andExpect(jsonPath("$.data.items[0].available").value("100.00"))
        .andExpect(jsonPath("$.data.items[0].occupied").value("0.00"))
        .andExpect(jsonPath("$.data.items[0].cumulativePaid").value("300.00"))
        .andExpect(jsonPath("$.data.items[0].deducted").value("50.00"))
        .andExpect(jsonPath("$.data.items[0].pendingDeduction").value("150.00"))
        .andExpect(jsonPath("$.data.items[0].paid").value("300.00"))
        .andExpect(jsonPath("$.data.items[0].commissionPaid").value("0.00"))
        .andExpect(jsonPath("$.data.items[0].prepayPaid").value("300.00"))
        .andExpect(jsonPath("$.data.items[0].lastPaidAt").value("2026-10-22T08:00:00Z"))
        .andExpect(jsonPath("$.data.items[0].unverified").isNumber())
        .andExpect(jsonPath("$.data.items[0].unverified").value(2))
        .andExpect(jsonPath("$.data.total").isNumber())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.page").isNumber())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.summary.paid").value("300.00"))
        .andExpect(jsonPath("$.data.summary.commissionPaid").value("20.00"))
        .andExpect(jsonPath("$.data.summary.prepayPaid").value("280.00"))
        .andExpect(jsonPath("$.data.summary.creators").isNumber())
        .andExpect(jsonPath("$.data.summary.creators").value(1))
        .andExpect(jsonPath("$.data.summary.orders").isNumber())
        .andExpect(jsonPath("$.data.summary.orders").value(2))
        .andExpect(jsonPath("$.data.summary.quantity").isNumber())
        .andExpect(jsonPath("$.data.summary.quantity").value(3))
        .andExpect(jsonPath("$.data.summary.unverified").isNumber())
        .andExpect(jsonPath("$.data.summary.unverified").value(4));
  }

  @Test
  void shouldBindPaymentWindowAndType() throws Exception {
    when(service.list(any(), any()))
        .thenReturn(
            new IncomePage(
                new PageResult<>(List.of(), 0, 1, 20),
                new Summary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0)));
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
                        "Tee",
                        "Red / M",
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
                        "Kol A"),
                    new CommissionLine(
                        7002L,
                        "WD124",
                        9002L,
                        "Hat",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "0.00",
                        "UNVERIFIED",
                        null,
                        "CREDIT",
                        "COMPANY",
                        null,
                        null)),
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
        .andExpect(jsonPath("$.data.lines[0].id").value("7001"))
        .andExpect(jsonPath("$.data.lines[0].orderNo").value("WD123"))
        .andExpect(jsonPath("$.data.lines[0].itemId").value("9001"))
        .andExpect(jsonPath("$.data.lines[0].product").value("Tee"))
        .andExpect(jsonPath("$.data.lines[0].sku").value("Red / M"))
        .andExpect(jsonPath("$.data.lines[0].image").value("https://img"))
        .andExpect(jsonPath("$.data.lines[0].base").value("100.00"))
        .andExpect(jsonPath("$.data.lines[0].rate").value("0.1000"))
        .andExpect(jsonPath("$.data.lines[0].rateVersion").value(3))
        .andExpect(jsonPath("$.data.lines[0].ruleVersion").value("900"))
        .andExpect(jsonPath("$.data.lines[0].commission").value("10.00"))
        .andExpect(jsonPath("$.data.lines[0].adjustment").value("-2.00"))
        .andExpect(jsonPath("$.data.lines[0].releaseBasis").value("CONFIRMED"))
        .andExpect(jsonPath("$.data.lines[0].businessAt").value("2026-10-10T08:00:00Z"))
        .andExpect(jsonPath("$.data.lines[0].direction").value("DEBIT"))
        .andExpect(jsonPath("$.data.lines[0].subject").value("KOL"))
        .andExpect(jsonPath("$.data.lines[0].kol").value("Kol A"))
        .andExpect(jsonPath("$.data.lines[1].product").value("Hat"))
        .andExpect(jsonPath("$.data.lines[1].sku").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"sku\":null")))
        .andExpect(jsonPath("$.data.payments[0].id").value("8001"))
        .andExpect(jsonPath("$.data.payments[0].sourceNo").value("PP-1"))
        .andExpect(jsonPath("$.data.payments[0].type").value("PREPAY"))
        .andExpect(jsonPath("$.data.payments[0].kolId").value("77"))
        .andExpect(jsonPath("$.data.payments[0].amountCny").value("300.00"))
        .andExpect(jsonPath("$.data.payments[0].paidAt").value("2026-10-22T08:00:00Z"))
        .andExpect(jsonPath("$.data.payments[0].deduct").value(true))
        .andExpect(jsonPath("$.data.payments[0].deducted").value("100.00"))
        .andExpect(jsonPath("$.data.payments[0].pending").value("200.00"))
        .andExpect(jsonPath("$.data.payments[0].actualAmount").value("300.00"))
        .andExpect(jsonPath("$.data.payments[0].actualCurrency").value("CNY"))
        .andExpect(jsonPath("$.data.payments[0].voucher").value("55"))
        .andExpect(
            jsonPath("$.data.payments[0].withdrawalId").value(org.hamcrest.Matchers.nullValue()));
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
