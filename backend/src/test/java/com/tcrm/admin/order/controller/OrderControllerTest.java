package com.tcrm.admin.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.tcrm.admin.order.service.OrderFilterDTO;
import com.tcrm.admin.order.service.OrderService;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import com.tcrm.admin.order.service.OrderSyncStateDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OrderControllerTest {
  private final OrderService orders = mock(OrderService.class);
  private final OrderSyncCoordinator sync = mock(OrderSyncCoordinator.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new OrderController(orders, sync));

  private String admin() {
    return AdminMvcSupport.actAs(current, 7L, Role.SUPER_ADMIN, DataScope.ALL);
  }

  @Test
  void shouldUseDefaultPagination_whenQueryingOrders() throws Exception {
    when(orders.page(any(), eq(false))).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(get("/admin-api/order/orders").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.total").value("0"));
    var query = ArgumentCaptor.forClass(OrderFilterDTO.class);
    verify(orders).page(query.capture(), eq(false));
    assertThat(query.getValue().size()).isEqualTo(20);
  }

  @Test
  void shouldUsePoolCondition_whenQueryingUnmatchedOrders() throws Exception {
    when(orders.page(any(), eq(true))).thenReturn(new PageResult<>(List.of(), 0, 2, 5));
    mvc.perform(
            get("/admin-api/order/unmatched")
                .param("page", "2")
                .param("size", "5")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(2));
    verify(orders).page(any(), eq(true));
  }

  @Test
  void shouldReturnEveryShopWithSyncStatus_whenQueryingSyncStates() throws Exception {
    when(orders.listSyncStates())
        .thenReturn(
            List.of(
                new OrderSyncStateDTO(
                    10L, "Main shop", true, true, Instant.parse("2026-10-16T00:00:00Z"), null),
                new OrderSyncStateDTO(20L, "Backup shop", false, false, null, null)));
    mvc.perform(get("/admin-api/order/sync-states").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].shopId").value("10"))
        .andExpect(jsonPath("$.data[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].authorized").value(true))
        .andExpect(jsonPath("$.data[0].lastSuccessAt").value("2026-10-16T00:00:00Z"))
        .andExpect(jsonPath("$.data[1].shopId").value("20"))
        .andExpect(jsonPath("$.data[1].lastSuccessAt").isEmpty())
        .andExpect(jsonPath("$.data[1].lastError").isEmpty());
    verify(orders).listSyncStates();
  }

  @Test
  void shouldAcceptBackgroundSync_whenRequestIsValid() throws Exception {
    mvc.perform(
            post("/admin-api/order/syncs")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"5\"}"))
        .andExpect(status().isAccepted());
    verify(sync).trigger(5L);
  }

  @Test
  void shouldForwardWholeOrderAssignment_whenRequestIsValid() throws Exception {
    mvc.perform(
            put("/admin-api/order/orders/10/attribution")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nonKol\":true,\"reason\":\"direct sale\"}"))
        .andExpect(status().isOk());
    verify(orders).assign(10L, null, true, "direct sale");
  }

  @Test
  void shouldForwardExplicitReattribution_whenRequested() throws Exception {
    mvc.perform(
            post("/admin-api/order/orders/10/reattribution")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk());
    verify(orders).reattribute(10L);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"reason\":\"valid\"}",
        "{\"kolId\":\"100\",\"nonKol\":true,\"reason\":\"valid\"}",
        "{\"kolId\":\"100\",\"reason\":\" \"}"
      })
  void shouldRejectInvalidAssignment_whenTargetOrReasonIsInvalid(String body) throws Exception {
    mvc.perform(
            put("/admin-api/order/orders/10/attribution")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orders);
  }

  @Test
  void shouldRejectEveryEndpoint_whenPermissionsAreAbsent() throws Exception {
    String token = AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of());
    for (MockHttpServletRequestBuilder request :
        List.of(
            get("/admin-api/order/orders"),
            get("/admin-api/order/unmatched"),
            get("/admin-api/order/sync-states"),
            post("/admin-api/order/syncs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":5}"),
            put("/admin-api/order/orders/10/attribution")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nonKol\":true,\"reason\":\"direct sale\"}"),
            post("/admin-api/order/orders/10/reattribution"))) {
      mvc.perform(request.header(AdminMvcSupport.TOKEN_HEADER, token))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(orders, sync);
  }
}
