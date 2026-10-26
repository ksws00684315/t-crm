package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.AdminRow;
import com.tcrm.admin.commission.service.WithdrawalService.Detail;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceTask;
import com.tcrm.admin.commission.service.WithdrawalService.OrderView;
import com.tcrm.admin.commission.service.WithdrawalService.ProductView;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for the admin withdrawal endpoints (task 63, contract §6.2): access control,
 * envelope shape and the file-first payment registration.
 */
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
        current, 6L, Role.STAFF, DataScope.ALL, Set.of("withdrawal:review", "data:export"));
  }

  private String actAsPayer() {
    return AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of("withdrawal:pay"));
  }

  private String actAsViewer() {
    return AdminMvcSupport.actAs(current, 8L, Role.STAFF, DataScope.ALL, Set.of("order:view"));
  }

  @Test
  void shouldListWithdrawals_withNotesAndPausedState() throws Exception {
    when(withdrawals.page(any(), any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new AdminRow(
                        77L,
                        "W20261025000001",
                        22L,
                        "KOL-test",
                        "Creator",
                        5L,
                        "BD",
                        "COMMISSION",
                        "400.00",
                        "CNY",
                        "PENDING_REVIEW",
                        false,
                        null,
                        "note",
                        "review note",
                        null,
                        null,
                        new WithdrawalService.AccountView(
                            31L, "PAYPAL", "cr***@example.com", "Cr****", "USD", null, 41L),
                        NOW)),
                1L,
                1,
                20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.items[0].type").value("COMMISSION"))
        .andExpect(jsonPath("$.data.items[0].amount").value("400.00"))
        .andExpect(jsonPath("$.data.items[0].currency").value("CNY"))
        .andExpect(jsonPath("$.data.items[0].status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.data.items[0].account.account").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.items[0].account.type").value("PAYPAL"))
        .andExpect(jsonPath("$.data.items[0].account.id").value("31"))
        .andExpect(jsonPath("$.data.items[0].account.qrFileId").value("41"))
        .andExpect(jsonPath("$.data.items[0].paused").value(false))
        .andExpect(jsonPath("$.data.items[0].reviewNote").value("review note"))
        .andExpect(jsonPath("$.data.items[0].previousId").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].id").value("77"))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  void shouldRefuseList_withoutWithdrawalPermission() throws Exception {
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, actAsViewer()))
        .andExpect(status().isForbidden());
    verify(withdrawals, never()).page(any(), any());
  }

  @Test
  void shouldReturnDetailWithAllocations() throws Exception {
    when(withdrawals.detail(any(), eq(77L))).thenReturn(detail());
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.type").value("COMMISSION"))
        .andExpect(jsonPath("$.data.applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.amount").value("400.00"))
        .andExpect(jsonPath("$.data.currency").value("CNY"))
        .andExpect(jsonPath("$.data.account.account").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.account.holder").value("Cr****"))
        .andExpect(jsonPath("$.data.allocations[0].id").value("101"))
        .andExpect(jsonPath("$.data.allocations[0].orderNo").value("WD1001"))
        .andExpect(jsonPath("$.data.allocations[0].product").value("Hat"))
        .andExpect(jsonPath("$.data.allocations[0].occupied").value("350.00"))
        .andExpect(jsonPath("$.data.allocations[0].remainingSnapshot").value("0.00"))
        .andExpect(jsonPath("$.data.allocations[0].orderRemainingSnapshot").doesNotExist())
        .andExpect(jsonPath("$.data.history[0].action").value("SUBMITTED"))
        .andExpect(jsonPath("$.data.history[0].actor").value("Creator"))
        .andExpect(jsonPath("$.data.history[0].note").value("note"))
        .andExpect(jsonPath("$.data.history[0].actorId").doesNotExist());
  }

  @Test
  void shouldPageOrderSnapshots_withProducts() throws Exception {
    when(withdrawals.orders(any(), eq(77L), eq(1), eq(10)))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new OrderView(
                        901L,
                        "WD1001",
                        List.of(new ProductView(101L, "img", "Hat", "Red")),
                        "350.00",
                        "0.00")),
                1L,
                1,
                10));
    mvc.perform(
            get(BASE + "/77/orders?page=1&size=10")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value("901"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("WD1001"))
        .andExpect(jsonPath("$.data.items[0].occupied").value("350.00"))
        .andExpect(jsonPath("$.data.items[0].remainingSnapshot").value("0.00"))
        .andExpect(jsonPath("$.data.items[0].products[0].id").value("101"))
        .andExpect(jsonPath("$.data.items[0].products[0].sku").value("Red"))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void shouldRevealFullRecipient_withSnapshotIds_forPayer() throws Exception {
    when(withdrawals.recipient(any(), eq(77L)))
        .thenReturn(
            new WithdrawalService.Recipient(
                31L, "USDT", "TRX-ADDR", "Creator", "USDT", "TRC20", 41L));
    mvc.perform(get(BASE + "/77/recipient").header(AdminMvcSupport.TOKEN_HEADER, actAsPayer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("31"))
        .andExpect(jsonPath("$.data.qrFileId").value("41"))
        .andExpect(jsonPath("$.data.type").value("USDT"))
        .andExpect(jsonPath("$.data.account").value("TRX-ADDR"))
        .andExpect(jsonPath("$.data.holder").value("Creator"))
        .andExpect(jsonPath("$.data.network").value("TRC20"));
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

  @Test
  void shouldRegisterPayment_withTheRealFrontendVoucherShape() throws Exception {
    when(withdrawals.uploadVoucher(any(), any(), any())).thenReturn(881L);
    when(withdrawals.pay(any(), eq(77L), any())).thenReturn(detail());
    mvc.perform(
            multipart("/admin-api/files")
                .file(
                    new MockMultipartFile(
                        "file",
                        "receipt.png",
                        "image/png",
                        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}))
                .header(AdminMvcSupport.TOKEN_HEADER, actAsPayer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fileId").value(881));
    // the merged admin frontend submits PaymentRegistration with `voucher` (appendix A)
    mvc.perform(
            put(BASE + "/77/payment")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsPayer())
                .contentType("application/json")
                .content(
                    "{\"idempotencyKey\":\"7565e681-dfd4-48bb-9f83-82e66cbb04ad\","
                        + "\"amountCny\":\"400.00\",\"actualAmount\":\"400.00\","
                        + "\"actualCurrency\":\"CNY\",\"paidAt\":\"2026-10-25T12:00:00+08:00\","
                        + "\"method\":\"bank\",\"financeNote\":\"wire done\",\"voucher\":881}"))
        .andExpect(status().isOk());
    ArgumentCaptor<WithdrawalService.PaymentCommand> command =
        ArgumentCaptor.forClass(WithdrawalService.PaymentCommand.class);
    verify(withdrawals).pay(any(), eq(77L), command.capture());
    assertThat(command.getValue().voucherFileId()).isEqualTo(881L);
    assertThat(command.getValue().financeNote()).isEqualTo("wire done");
    // a body without the voucher fails validation instead of registering a payment
    mvc.perform(
            put(BASE + "/77/payment")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsPayer())
                .contentType("application/json")
                .content(
                    "{\"idempotencyKey\":\"7565e681-dfd4-48bb-9f83-82e66cbb04ad\","
                        + "\"amountCny\":\"400.00\",\"actualAmount\":\"400.00\","
                        + "\"actualCurrency\":\"CNY\",\"paidAt\":\"2026-10-25T12:00:00+08:00\","
                        + "\"method\":\"bank\"}"))
        .andExpect(status().isBadRequest());
    verify(withdrawals).pay(any(), eq(77L), command.capture());
  }

  @Test
  void shouldRefusePaymentWithoutWithdrawalPay() throws Exception {
    mvc.perform(
            put(BASE + "/77/payment")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer())
                .contentType("application/json")
                .content(
                    "{\"idempotencyKey\":\"7565e681-dfd4-48bb-9f83-82e66cbb04ad\","
                        + "\"amountCny\":\"400.00\",\"actualAmount\":\"400.00\","
                        + "\"actualCurrency\":\"CNY\",\"paidAt\":\"2026-10-25T12:00:00+08:00\","
                        + "\"method\":\"bank\",\"voucher\":881}"))
        .andExpect(status().isForbidden());
    verify(withdrawals, never()).pay(any(), any(Long.class), any());
  }

  @Test
  void shouldRefuseRecipientWithoutWithdrawalPay() throws Exception {
    mvc.perform(get(BASE + "/77/recipient").header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReviewAndReturnDetailShape() throws Exception {
    when(withdrawals.review(any(), eq(77L), eq("APPROVE"), eq("ok"))).thenReturn(detail());
    mvc.perform(
            put(BASE + "/77/review")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsReviewer())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\",\"note\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.amount").value("400.00"))
        .andExpect(jsonPath("$.data.type").value("COMMISSION"));
    verify(withdrawals).review(any(), eq(77L), eq("APPROVE"), eq("ok"));
  }

  @Test
  void shouldListOrderItemOccupations_withChainFieldNames() throws Exception {
    when(withdrawals.occupations(any(), eq(List.of(101L))))
        .thenReturn(
            List.of(
                new WithdrawalService.Occupation(
                    101L, 77L, "W20261025000001", "PAID", "350.00", "350.00", "0.00", NOW, NOW)));
    mvc.perform(
            get("/admin-api/commission/withdrawal-occupations?orderItemIds=101")
                .header(AdminMvcSupport.TOKEN_HEADER, actAsViewerWithCommissionView()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orderItemId").value("101"))
        .andExpect(jsonPath("$.data[0].withdrawalId").value("77"))
        .andExpect(jsonPath("$.data[0].applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data[0].status").value("PAID"))
        .andExpect(jsonPath("$.data[0].occupiedCny").value("350.00"))
        .andExpect(jsonPath("$.data[0].sourceReleasedCny").value("350.00"))
        .andExpect(jsonPath("$.data[0].remainingSnapshotCny").value("0.00"))
        .andExpect(jsonPath("$.data[0].submittedAt").exists())
        .andExpect(jsonPath("$.data[0].paidAt").exists());
  }

  private String actAsViewerWithCommissionView() {
    return AdminMvcSupport.actAs(
        current, 10L, Role.STAFF, DataScope.ALL, Set.of("commission:view"));
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
  void shouldListUnifiedFinanceTasksWithRawStatus() throws Exception {
    when(withdrawals.financeTasks(any(), any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new FinanceTask(
                        3L,
                        "PP1",
                        22L,
                        "KOL-test",
                        "Creator",
                        5L,
                        "BD",
                        "PREPAY",
                        "APPROVED",
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
        .andExpect(jsonPath("$.data.items[0].id").value("3"))
        .andExpect(jsonPath("$.data.items[0].type").value("PREPAY"))
        .andExpect(jsonPath("$.data.items[0].applicationNo").value("PP1"))
        .andExpect(jsonPath("$.data.items[0].kolId").value("22"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL-test"))
        .andExpect(jsonPath("$.data.items[0].kolName").value("Creator"))
        .andExpect(jsonPath("$.data.items[0].amount").value("70.00"))
        .andExpect(jsonPath("$.data.items[0].currency").value("CNY"))
        .andExpect(jsonPath("$.data.items[0].status").value("APPROVED"))
        .andExpect(jsonPath("$.data.items[0].createdAt").exists())
        .andExpect(jsonPath("$.data.items[0].taskNo").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].taskType").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].amountCny").doesNotExist())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20));
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

  @Test
  void shouldResumeFreeze_andPassOptionalReason() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/admin-api/commission/withdrawal-freezes/22/resume")
                .header(AdminMvcSupport.TOKEN_HEADER, freezeToken())
                .contentType("application/json")
                .content("{\"reason\":\"dispute resolved\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/admin-api/commission/withdrawal-freezes/22/resume")
                .header(AdminMvcSupport.TOKEN_HEADER, freezeToken())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk());
    verify(withdrawals).resume(any(), eq(22L), eq("dispute resolved"));
    verify(withdrawals).resume(any(), eq(22L), eq(null));
  }

  private String freezeToken() {
    return AdminMvcSupport.actAs(
        current, 9L, Role.STAFF, DataScope.ALL, Set.of("commission:freeze"));
  }

  private static Detail detail() {
    return new Detail(
        77L,
        "W20261025000001",
        22L,
        "KOL-test",
        "Creator",
        5L,
        "BD",
        "COMMISSION",
        "400.00",
        "CNY",
        "PENDING_REVIEW",
        false,
        null,
        NOW,
        "note",
        null,
        null,
        null,
        List.of(
            new WithdrawalService.AllocationView(
                101L, "WD1001", "Hat", "Red", "img", "350.00", "0.00")),
        new WithdrawalService.AccountView(
            null, "PAYPAL", "cr***@example.com", "Cr****", "USD", null, null),
        List.of(new WithdrawalService.HistoryItem("SUBMITTED", 22L, "Creator", "KOL", "note", NOW)),
        null);
  }
}
