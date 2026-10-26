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

  /** Task 70 (B01-B04): the new query fields bind, normalize and validate like the task 40 ones. */
  @Test
  void shouldBindTask70Filters_whenQueryingOrders() throws Exception {
    when(orders.page(any(), eq(false))).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-16T00:00:00Z")
                .param("orderTimeTo", "2026-10-17T00:00:00Z")
                .param("kolKeyword", " Zoe ")
                .param("refundState", "REFUNDING")
                .param("abnormal", "true")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk());
    var query = ArgumentCaptor.forClass(OrderFilterDTO.class);
    verify(orders).page(query.capture(), eq(false));
    assertThat(query.getValue().orderTimeFrom()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
    assertThat(query.getValue().orderTimeTo()).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"));
    assertThat(query.getValue().kolKeyword()).isEqualTo("Zoe");
    assertThat(query.getValue().refundState()).isEqualTo("REFUNDING");
    assertThat(query.getValue().abnormal()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"REFUNDED2", "PROCESSING"})
  void shouldRejectUnknownRefundStates_whenQueryingOrders(String refundState) throws Exception {
    mvc.perform(
            get("/admin-api/order/orders")
                .param("refundState", refundState)
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orders);
  }

  @Test
  void shouldRejectInvertedOrderTimeWindow_whenQueryingOrders() throws Exception {
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-17T00:00:00Z")
                .param("orderTimeTo", "2026-10-16T00:00:00Z")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orders);
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
    var since = Instant.parse("2026-10-16T00:00:00Z");
    when(orders.listSyncStates())
        .thenReturn(
            List.of(
                new OrderSyncStateDTO(
                    10L,
                    "Main shop",
                    true,
                    true,
                    since,
                    null,
                    new OrderSyncStateDTO.KindSyncDTO(
                        "API", since, null, 2L, java.util.Map.of("payTime", 1L)),
                    new OrderSyncStateDTO.KindSyncDTO(
                        "IMPORT", since, null, 0L, java.util.Map.of("orderItemId", 3L)),
                    new OrderSyncStateDTO.ImportCutoffDTO("ORDER", since, 33L)),
                new OrderSyncStateDTO(
                    20L,
                    "Backup shop",
                    false,
                    false,
                    null,
                    null,
                    new OrderSyncStateDTO.KindSyncDTO(null, null, null, 0L, java.util.Map.of()),
                    new OrderSyncStateDTO.KindSyncDTO(null, null, null, 0L, java.util.Map.of()),
                    null)));
    mvc.perform(get("/admin-api/order/sync-states").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].shopId").value("10"))
        .andExpect(jsonPath("$.data[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].authorized").value(true))
        .andExpect(jsonPath("$.data[0].lastSuccessAt").value("2026-10-16T00:00:00Z"))
        .andExpect(jsonPath("$.data[0].orders.source").value("API"))
        .andExpect(jsonPath("$.data[0].orders.failures24h").value(2))
        .andExpect(jsonPath("$.data[0].orders.missingFields.payTime").value(1))
        .andExpect(jsonPath("$.data[0].refunds.source").value("IMPORT"))
        .andExpect(jsonPath("$.data[0].refunds.missingFields.orderItemId").value(3))
        .andExpect(jsonPath("$.data[0].lastImport.kind").value("ORDER"))
        .andExpect(jsonPath("$.data[0].lastImport.taskId").value("33"))
        .andExpect(jsonPath("$.data[1].shopId").value("20"))
        .andExpect(jsonPath("$.data[1].lastSuccessAt").isEmpty())
        .andExpect(jsonPath("$.data[1].lastError").isEmpty())
        .andExpect(jsonPath("$.data[1].lastImport").isEmpty());
    verify(orders).listSyncStates();
  }

  @Test
  void shouldReturnRecentFailures_whenQueryingSyncFailures() throws Exception {
    when(orders.listSyncFailures())
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.service.SyncFailureDTO(
                    1L, 10L, "Main shop", "ORDER_SYNC", "token expired", null, Instant.EPOCH)));
    mvc.perform(get("/admin-api/order/sync-failures").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].kind").value("ORDER_SYNC"))
        .andExpect(jsonPath("$.data[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data[0].error").value("token expired"))
        .andExpect(jsonPath("$.data[0].taskId").isEmpty());
    verify(orders).listSyncFailures();
  }

  @Test
  void shouldAcceptBackgroundSync_whenRequestIsValid() throws Exception {
    mvc.perform(
            post("/admin-api/order/syncs")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"5\"}"))
        .andExpect(status().isAccepted());
    verify(sync).trigger(5L, null);
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

  @ParameterizedTest
  @ValueSource(strings = {"ORDER", "REFUND"})
  void shouldForwardTheRequestedSyncType(String type) throws Exception {
    mvc.perform(
            post("/admin-api/order/syncs")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":5,\"type\":\"" + type + "\"}"))
        .andExpect(status().isAccepted());
    verify(sync).trigger(5L, type);
  }

  @Test
  void shouldRejectEveryEndpoint_whenPermissionsAreAbsent() throws Exception {
    String token = AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of());
    for (MockHttpServletRequestBuilder request :
        List.of(
            get("/admin-api/order/orders"),
            get("/admin-api/order/orders/10"),
            get("/admin-api/order/orders/10/chain"),
            get("/admin-api/order/unmatched"),
            get("/admin-api/order/unmatched/20/evidence"),
            get("/admin-api/order/sync-states"),
            get("/admin-api/order/sync-failures"),
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
