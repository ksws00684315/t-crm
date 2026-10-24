package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** Controller slice for the admin withdrawal endpoints (task 63): access, envelope, payloads. */
class AdminWithdrawalControllerTest {
  private static final String BASE = "/admin-api/commission/withdrawals";
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  private final WithdrawalService withdrawals = mock(WithdrawalService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(
          current,
          new AdminWithdrawalController(withdrawals, current),
          new FinanceTaskController(withdrawals, current),
          new WithdrawalFreezeController(withdrawals, current));

  private String actAsReviewer() {
    return AdminMvcSupport.actAs(
        current,
        6L,
        Role.STAFF,
        DataScope.ALL,
        Set.of("withdrawal:review", "prepay:pay", "data:export"));
  }

  private String actAsViewer() {
    return AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of("order:view"));
  }

  @Test
  void shouldListWithdrawals_withMaskedAccountOnly() throws Exception {
    when(withdrawals.page(any(), any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new WithdrawalService.AdminRow(
                        77L,
                        "CW20261025000001",
                        22L,
                        "KOL-test",
                        "Creator",
                        5L,
                        "BD",
                        "COMMISSION_WITHDRAWAL",
                        "400.00",
                        "CNY",
                        "PENDING_REVIEW",
                        NOW,
                        "PAYPAL",
                        "cr***@example.com")),
                1L,
                1,
                20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].applicationNo").value("CW20261025000001"))
        .andExpect(jsonPath("$.data.items[0].amountCny").value("400.00"))
        .andExpect(jsonPath("$.data.items[0].accountMasked").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.items[0].id").value("77"));
  }

  @Test
  void shouldRefuseList_withoutWithdrawalOrCommissionPermission() throws Exception {
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, actAsViewer()))
        .andExpect(status().isForbidden());
    verify(withdrawals, never()).page(any(), any());
  }

  @Test
  void shouldReturnDetailWithMaskedAccount_andKeepEnvelopeShape() throws Exception {
    when(withdrawals.detail(any(), eq(77L))).thenReturn(detail());
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.type").value("COMMISSION_WITHDRAWAL"))
        .andExpect(jsonPath("$.data.currency").value("CNY"))
        .andExpect(jsonPath("$.data.account.account").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.allocationCount").value("2"));
  }

  @Test
  void shouldPageAllocatedOrders_withMoneyStrings() throws Exception {
    when(withdrawals.orders(any(), eq(77L), eq(1), eq(10)))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new com.tcrm.admin.commission.model.WithdrawalRows.AllocationRow(
                        901L,
                        101L,
                        "WD1001",
                        "Hat",
                        "Red",
                        "https://img/hat.png",
                        new java.math.BigDecimal("350.00"),
                        new java.math.BigDecimal("0.00"),
                        new java.math.BigDecimal("350.00"))),
                1L,
                1,
                10));
    mvc.perform(
            get(BASE + "/77/orders?page=1&size=10")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].orderNo").value("WD1001"))
        .andExpect(jsonPath("$.data.items[0].occupiedCny").value("350.00"))
        .andExpect(jsonPath("$.data.items[0].remainingSnapshotCny").value("0.00"))
        .andExpect(jsonPath("$.data.items[0].sourceReleasedCny").value("350.00"));
  }

  @Test
  void shouldValidateReviewDecision() throws Exception {
    mvc.perform(
            put(BASE + "/77/review")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer())
                .contentType("application/json")
                .content("{\"decision\":\"MAYBE\"}"))
        .andExpect(status().isBadRequest());
    verify(withdrawals, never()).review(any(), any(Long.class), any(), any());
  }

  private static byte[] paymentPayload() {
    return ("{\"requestId\":\"7565e681-dfd4-48bb-9f83-82e66cbb04ad\","
            + "\"amountCny\":\"400.00\",\"actualAmount\":\"400.00\","
            + "\"actualCurrency\":\"CNY\",\"paidAt\":\"2026-10-25T12:00:00+08:00\","
            + "\"method\":\"bank\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void shouldPassPaymentRegistrationWithVoucherPart() throws Exception {
    when(withdrawals.pay(any(), eq(77L), any(), any())).thenReturn(detail());
    mvc.perform(
            multipartPut(BASE + "/77/payment")
                .file(new MockMultipartFile("payload", "", "application/json", paymentPayload()))
                .file(new MockMultipartFile("voucher", "receipt.png", "image/png", new byte[] {1}))
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk());
    verify(withdrawals).pay(any(), eq(77L), any(), any());
  }

  @Test
  void shouldRefusePaymentWithoutPrepayPayPermission() throws Exception {
    String token =
        AdminMvcSupport.actAs(current, 8L, Role.STAFF, DataScope.ALL, Set.of("withdrawal:review"));
    mvc.perform(
            multipartPut(BASE + "/77/payment")
                .file(new MockMultipartFile("payload", "", "application/json", paymentPayload()))
                .file(new MockMultipartFile("voucher", "receipt.png", "image/png", new byte[] {1}))
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verify(withdrawals, never()).pay(any(), any(Long.class), any(), any());
  }

  @Test
  void shouldExportCsvWithAttachmentHeader() throws Exception {
    when(withdrawals.export(any(), any())).thenReturn("no,rows".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response =
        mvc.perform(get(BASE + "/export").header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(response.getContentType()).startsWith("text/csv");
    assertThat(response.getHeader("Content-Disposition")).contains("withdrawals.csv");
  }

  @Test
  void shouldListUnifiedFinanceTasks() throws Exception {
    when(withdrawals.financeTasks(any(), any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new WithdrawalService.FinanceTask(
                        3L,
                        "PP1",
                        22L,
                        "KOL-test",
                        "Creator",
                        5L,
                        "BD",
                        "PREPAY",
                        "APPROVED",
                        "PENDING_PAYMENT",
                        "70.00",
                        "CNY",
                        NOW)),
                1L,
                1,
                20));
    mvc.perform(
            get("/admin-api/commission/finance-tasks")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].taskType").value("PREPAY"))
        .andExpect(jsonPath("$.data.items[0].status").value("PENDING_PAYMENT"));
  }

  @Test
  void shouldFreezeWithReasonOnly() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/admin-api/commission/withdrawal-freezes")
                .header(AdminMvcSupport.TOKEN_HEADER, freezeToken())
                .contentType("application/json")
                .content("{\"kolId\":22,\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/admin-api/commission/withdrawal-freezes")
                .header(AdminMvcSupport.TOKEN_HEADER, freezeToken())
                .contentType("application/json")
                .content("{\"kolId\":22,\"reason\":\"dispute\"}"))
        .andExpect(status().isOk());
    verify(withdrawals).freeze(any(), eq(22L), eq("dispute"));
  }

  private String freezeToken() {
    return AdminMvcSupport.actAs(
        current, 9L, Role.STAFF, DataScope.ALL, Set.of("commission:freeze"));
  }

  /** PUT multipart: the standard builder defaults to POST, so the method is overridden. */
  private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
      multipartPut(String url) {
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url);
    builder.with(
        request -> {
          request.setMethod("PUT");
          return request;
        });
    return builder;
  }

  private static WithdrawalService.Detail detail() {
    return new WithdrawalService.Detail(
        77L,
        "CW20261025000001",
        22L,
        "KOL-test",
        "Creator",
        5L,
        "BD",
        "COMMISSION_WITHDRAWAL",
        "400.00",
        "CNY",
        "PENDING_REVIEW",
        NOW,
        "note",
        null,
        null,
        null,
        2L,
        new WithdrawalService.AccountView(
            null, "PAYPAL", "cr***@example.com", "Cr****", "USD", null, null),
        List.of(new WithdrawalService.HistoryItem("SUBMITTED", 22L, "Creator", "KOL", "note", NOW)),
        null);
  }
}
