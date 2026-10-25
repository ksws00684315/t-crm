package com.tcrm.admin.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.stp.StpUtil;
import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.order.service.KolOrderDTO;
import com.tcrm.admin.order.service.KolOrderFilterDTO;
import com.tcrm.admin.order.service.KolOrderService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KolOrderControllerTest {
  private final KolOrderService orders = mock(KolOrderService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc = KolMvcSupport.build(current, new KolOrderController(orders));

  @Test
  void shouldRequireKolToken() throws Exception {
    mvc.perform(get("/kol-api/orders")).andExpect(status().isUnauthorized());
    verifyNoInteractions(orders);
  }

  @Test
  void shouldRejectAdministratorToken() throws Exception {
    String administratorToken = StpUtil.createLoginSession(7L);
    mvc.perform(get("/kol-api/orders").header(KolMvcSupport.TOKEN_HEADER, administratorToken))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(orders);
  }

  @Test
  void shouldForwardFiltersAndReturnTheSmallResponse() throws Exception {
    var order =
        new KolOrderDTO(
            "78",
            Instant.parse("2026-10-15T12:00:00Z"),
            "PAID",
            new java.math.BigDecimal("25.00"),
            new java.math.BigDecimal("5.00"),
            "WITHDRAWABLE",
            List.of(
                new KolOrderDTO.Line(
                    "Widget",
                    "Blue",
                    2,
                    new java.math.BigDecimal("25.00"),
                    new java.math.BigDecimal("5.00"),
                    "WITHDRAWABLE",
                    new java.math.BigDecimal("0.1000"),
                    new java.math.BigDecimal("2.50"))));
    when(orders.page(any())).thenReturn(new PageResult<>(List.of(order), 1, 1, 20));

    mvc.perform(
            get("/kol-api/orders")
                .param("payFrom", "2026-10-01T00:00:00Z")
                .param("payTo", "2026-11-01T00:00:00Z")
                .param("orderStatus", "PAID")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].orderNo").value("78"))
        .andExpect(jsonPath("$.data.items[0].payAmount").value("25.00"))
        .andExpect(jsonPath("$.data.items[0].id").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].buyer").doesNotExist())
        .andExpect(jsonPath("$.data.total").value("1"));

    verify(orders)
        .page(
            new KolOrderFilterDTO(
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                "PAID",
                null,
                null,
                null,
                1,
                20));
  }

  @Test
  void shouldForwardOrderNumberAndProductNameFilters() throws Exception {
    when(orders.page(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));

    mvc.perform(
            get("/kol-api/orders")
                .param("orderNo", "1234")
                .param("productName", "Widget")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));

    verify(orders).page(new KolOrderFilterDTO(null, null, null, "1234", "Widget", null, 1, 20));
  }

  @Test
  void shouldForwardSettlementStatusFilterAndCommissionFields() throws Exception {
    var order =
        new KolOrderDTO(
            "78",
            Instant.parse("2026-10-15T12:00:00Z"),
            "PAID",
            new java.math.BigDecimal("25.00"),
            new java.math.BigDecimal("5.00"),
            "WITHDRAWABLE",
            List.of(
                new KolOrderDTO.Line(
                    "Widget",
                    "Blue",
                    2,
                    new java.math.BigDecimal("25.00"),
                    new java.math.BigDecimal("5.00"),
                    "WITHDRAWABLE",
                    new java.math.BigDecimal("0.1000"),
                    new java.math.BigDecimal("2.50"))));
    when(orders.page(any())).thenReturn(new PageResult<>(List.of(order), 1, 1, 20));

    mvc.perform(
            get("/kol-api/orders")
                .param("settlementStatus", "WITHDRAWABLE")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].settlementStatus").value("WITHDRAWABLE"))
        .andExpect(jsonPath("$.data.items[0].items[0].rateSnapshot").value("0.1000"))
        .andExpect(jsonPath("$.data.items[0].items[0].commissionAmount").value("2.50"));

    verify(orders).page(new KolOrderFilterDTO(null, null, null, null, null, "WITHDRAWABLE", 1, 20));
  }

  @Test
  void shouldRejectUnknownSettlementStatus() throws Exception {
    mvc.perform(
            get("/kol-api/orders")
                .param("settlementStatus", "MYSTERY")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectPageSizeAboveFifty() throws Exception {
    mvc.perform(
            get("/kol-api/orders").param("size", "51").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isBadRequest());
  }

  private String token() {
    return KolMvcSupport.actAs(current, kol());
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
