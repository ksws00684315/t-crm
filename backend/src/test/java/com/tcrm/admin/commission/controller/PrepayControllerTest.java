package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class PrepayControllerTest {
  private static final String BASE = "/admin-api/commission/prepays";
  private static final String REQUEST_ID = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";
  private final PrepayService service = mock(PrepayService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new PrepayController(service, current));

  @Test
  void shouldListStringIdsAndAmountsWithDefaultPagination() throws Exception {
    when(service.page(any(), any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new PrepayService.PrepayRow(
                        77L,
                        "PP-test",
                        "PREPAY",
                        77L,
                        22L,
                        "KOL-test",
                        "Test",
                        5L,
                        "Business operator",
                        "300.00",
                        "CNY",
                        "WAITING_DETAILS",
                        Instant.parse("2026-10-22T04:00:00Z"))),
                1,
                1,
                20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, token("commission:view")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value("77"))
        .andExpect(jsonPath("$.data.items[0].amountCny").value("300.00"));
    verify(service).page(any(), eq(new PrepayService.Filter(null, null, null, 1, 20)));
  }

  @Test
  void shouldBindExplicitFiltersForFinance() throws Exception {
    mvc.perform(
            get(BASE)
                .param("prepayNo", "PP-test")
                .param("kolCode", "KOL-test")
                .param("status", "PAID")
                .param("page", "2")
                .param("size", "10")
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:pay")))
        .andExpect(status().isOk());
    verify(service).page(any(), eq(new PrepayService.Filter("PP-test", "KOL-test", "PAID", 2, 10)));
  }

  @Test
  void shouldSubmitOnlyCreatorAndAmountWithoutFinanceFields() throws Exception {
    mvc.perform(
            multipart(BASE)
                .file(
                    payload(
                        "{\"requestId\":\""
                            + REQUEST_ID
                            + "\",\"kolId\":\"22\",\"amountCny\":\"300.00\"}"))
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:apply")))
        .andExpect(status().isOk());
    verify(service)
        .apply(any(), eq(new PrepayService.PrepayApplication(REQUEST_ID, 22L, "300.00")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"requestId\":\"id\",\"kolId\":\"22\",\"amountCny\":\"1.001\"}",
        "{\"requestId\":\"id\",\"kolId\":\"0\",\"amountCny\":\"1\"}"
      })
  void shouldRejectInvalidApplicationPayloadBeforeService(String body) throws Exception {
    mvc.perform(
            multipart(BASE)
                .file(payload(body))
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:apply")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void shouldForwardFinanceCompletionWithExplicitFalseAndAttachments() throws Exception {
    String body =
        "{\"reason\":\"Business advance\",\"expectedPayDate\":\"2026-10-23\",\"payoutAccountId\":\"33\",\"deductFromCommission\":false}";
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/application-details")
                .file(payload(body))
                .file(
                    new MockMultipartFile(
                        "attachments", "reason.pdf", "application/pdf", new byte[] {1, 2}))
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:apply")))
        .andExpect(status().isOk());
    var details = ArgumentCaptor.forClass(PrepayService.ApplicationDetails.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PrepayService.UploadedFile>> uploaded = ArgumentCaptor.forClass(List.class);
    verify(service).saveApplicationDetails(any(), eq(77L), details.capture(), uploaded.capture());
    assertThat(details.getValue().deductFromCommission()).isFalse();
    assertThat(uploaded.getValue())
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.fileName()).isEqualTo("reason.pdf");
              assertThat(file.content()).containsExactly((byte) 1, (byte) 2);
            });
  }

  @Test
  void shouldAllowFinanceCompletionWithoutAttachmentsAndRequireDeductionChoice() throws Exception {
    String body =
        "{\"reason\":\"Business advance\",\"expectedPayDate\":\"2026-10-23\",\"payoutAccountId\":\"33\",\"deductFromCommission\":true}";
    String token = token("prepay:apply");
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/application-details")
                .file(payload(body))
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(service).saveApplicationDetails(any(), eq(77L), any(), eq(List.of()));
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/application-details")
                .file(payload(body.replace(",\"deductFromCommission\":true", "")))
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldKeepApprovalAndPaymentPermissionsSeparate() throws Exception {
    String reviewer = token("prepay:review");
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/paid")
                .file(payload(paymentJson()))
                .file(voucher())
                .header(AdminMvcSupport.TOKEN_HEADER, reviewer))
        .andExpect(status().isForbidden());
    String payer = token("prepay:pay");
    mvc.perform(
            put(BASE + "/77/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, payer))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void shouldAllowDetailViewersToReadRecipientAndAcceptManualDetails() throws Exception {
    for (String permission :
        List.of(
            "prepay:apply",
            "commission:view",
            "prepay:review",
            "prepay:finance-review",
            "prepay:pay")) {
      mvc.perform(
              get(BASE + "/77/recipient").header(AdminMvcSupport.TOKEN_HEADER, token(permission)))
          .andExpect(status().isOk());
    }
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/application-details")
                .file(
                    payload(
                        "{\"reason\":\"Manual\",\"expectedPayDate\":\"2026-10-23\",\"recipientText\":\"Bank\\nAccount123\",\"deductFromCommission\":false}"))
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:apply")))
        .andExpect(status().isOk());
    var captured = ArgumentCaptor.forClass(PrepayService.ApplicationDetails.class);
    verify(service).saveApplicationDetails(any(), eq(77L), captured.capture(), eq(List.of()));
    assertThat(captured.getValue().recipientText()).isEqualTo("Bank\nAccount123");
    assertThat(captured.getValue().payoutAccountId()).isNull();
  }

  @Test
  void shouldApproveOrRejectWithValidatedDecision() throws Exception {
    String token = token("prepay:review");
    mvc.perform(
            put(BASE + "/77/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"REJECT\",\"reason\":\"Correction\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(service).approval(any(), eq(77L), eq("REJECT"), eq("Correction"));
    mvc.perform(
            put(BASE + "/77/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"UNKNOWN\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldCancelWithOptionalReason() throws Exception {
    String token = token("prepay:apply");
    mvc.perform(put(BASE + "/77/cancel").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(service).cancel(any(), eq(77L), eq(null));
    mvc.perform(
            put(BASE + "/78/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"No longer needed\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(service).cancel(any(), eq(78L), eq("No longer needed"));
  }

  @Test
  void shouldRecordPaymentWithVoucherAndFetchFrozenRecipient() throws Exception {
    String token = token("prepay:pay");
    mvc.perform(
            multipart(HttpMethod.PUT, BASE + "/77/paid")
                .file(payload(paymentJson()))
                .file(voucher())
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    var command = ArgumentCaptor.forClass(PrepayService.PaymentCommand.class);
    var upload = ArgumentCaptor.forClass(PrepayService.UploadedFile.class);
    verify(service).pay(any(), eq(77L), command.capture(), upload.capture());
    assertThat(command.getValue().requestId()).isEqualTo(REQUEST_ID);
    assertThat(command.getValue().payAmountCny()).isEqualTo("300");
    assertThat(upload.getValue().content()).containsExactly((byte) 1, (byte) 2);
    mvc.perform(get(BASE + "/77/recipient").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(service).recipient(any(), eq(77L));
  }

  @Test
  void shouldGetDetailAndApprovedPayoutOptions() throws Exception {
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, token("commission:view")))
        .andExpect(status().isOk());
    verify(service).detail(any(), eq(77L));
    mvc.perform(
            get(BASE + "/payout-accounts")
                .param("kolId", "22")
                .header(AdminMvcSupport.TOKEN_HEADER, token("prepay:apply")))
        .andExpect(status().isOk());
    verify(service).payoutOptions(any(), eq(22L));
  }

  @Test
  void shouldExportUtf8CsvAttachment() throws Exception {
    byte[] csv = "\uFEFF预付编号\r\nPP-test".getBytes(StandardCharsets.UTF_8);
    when(service.export(any(), any())).thenReturn(csv);
    mvc.perform(
            get(BASE + "/export")
                .header(AdminMvcSupport.TOKEN_HEADER, token("data:export", "commission:view")))
        .andExpect(status().isOk())
        .andExpect(content().bytes(csv))
        .andExpect(content().contentType("text/csv;charset=UTF-8"));
    verify(service).export(any(), eq(new PrepayService.Filter(null, null, null, 1, 100)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/77", "/export", "/payout-accounts?kolId=22"})
  void shouldDenyReadersWithoutRequiredPermission(String path) throws Exception {
    mvc.perform(get(BASE + path).header(AdminMvcSupport.TOKEN_HEADER, token("kol:view")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void shouldRejectInvalidPaginationBeforeService() throws Exception {
    String token = token("commission:view");
    mvc.perform(get(BASE).param("page", "0").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    mvc.perform(get(BASE).param("size", "101").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  private String token(String... permissions) {
    return AdminMvcSupport.actAs(current, 5L, Role.STAFF, DataScope.OWN, Set.of(permissions));
  }

  private static MockMultipartFile payload(String json) {
    return new MockMultipartFile(
        "payload", "payload.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  private static MockMultipartFile voucher() {
    return new MockMultipartFile("voucher", "receipt.pdf", "application/pdf", new byte[] {1, 2});
  }

  private static String paymentJson() {
    return "{\"requestId\":\""
        + REQUEST_ID
        + "\",\"payAmountCny\":\"300\",\"payTime\":\"2026-10-22T04:00:00Z\",\"payMethod\":\"BANK\"}";
  }
}
