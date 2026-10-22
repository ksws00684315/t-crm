package com.tcrm.admin.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.order.service.RefundDTO;
import com.tcrm.admin.order.service.RefundDetailDTO;
import com.tcrm.admin.order.service.RefundFilterDTO;
import com.tcrm.admin.order.service.RefundQueryService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

class RefundControllerTest {
  private final RefundQueryService refunds = mock(RefundQueryService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new RefundController(refunds));

  private String admin() {
    return AdminMvcSupport.actAs(current, 7L, Role.SUPER_ADMIN, DataScope.ALL);
  }

  @Test
  void shouldPageRefunds_whenFiltersProvided() throws Exception {
    when(refunds.page(any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new RefundDTO(
                        1L,
                        "R1",
                        10L,
                        "1000",
                        "WEIDIAN",
                        5L,
                        "Main shop",
                        null,
                        100L,
                        "KOL0100",
                        "Creator",
                        "REFUND_ONLY",
                        "SUCCESS",
                        new BigDecimal("5.00"),
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(1))),
                1,
                1,
                20));
    mvc.perform(
            get("/admin-api/order/refunds")
                .param("refundNo", "R1")
                .param("orderNo", "1000")
                .param("channel", "WEIDIAN")
                .param("refundStatus", "SUCCESS")
                .param("refundType", "REFUND_ONLY")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].refundNo").value("R1"))
        .andExpect(jsonPath("$.data.items[0].amount").value("5.00"));
    var filter = ArgumentCaptor.forClass(RefundFilterDTO.class);
    verify(refunds).page(filter.capture());
    assertThat(filter.getValue().refundNo()).isEqualTo("R1");
    assertThat(filter.getValue().orderNo()).isEqualTo("1000");
    assertThat(filter.getValue().refundStatus()).isEqualTo("SUCCESS");
  }

  @Test
  void shouldReturnDetail_whenRefundExists() throws Exception {
    when(refunds.detail(eq(1L)))
        .thenReturn(
            new RefundDetailDTO(
                1L,
                "R1",
                10L,
                "1000",
                "WEIDIAN",
                5L,
                "Main shop",
                null,
                "wd-5",
                100L,
                "KOL0100",
                "Creator",
                "REFUND_ONLY",
                "SUCCESS",
                new BigDecimal("5.00"),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                List.of()));
    mvc.perform(get("/admin-api/order/refunds/1").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.merchantNo").value("wd-5"))
        .andExpect(jsonPath("$.data.refundStatus").value("SUCCESS"));
    verify(refunds).detail(1L);
  }

  @Test
  void shouldRejectEveryEndpoint_whenPermissionsAreAbsent() throws Exception {
    String token = AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of());
    mvc.perform(get("/admin-api/order/refunds").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/order/refunds/1").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(refunds);
  }
}
