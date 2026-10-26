package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.AccountView;
import com.tcrm.admin.commission.service.WithdrawalService.ApplyCommand;
import com.tcrm.admin.commission.service.WithdrawalService.Conditions;
import com.tcrm.admin.commission.service.WithdrawalService.Detail;
import com.tcrm.admin.commission.service.WithdrawalService.HistoryItem;
import com.tcrm.admin.commission.service.WithdrawalService.KolRow;
import com.tcrm.admin.commission.service.WithdrawalService.PaymentView;
import com.tcrm.admin.commission.service.WithdrawalService.RecipientOption;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for the creator withdrawal endpoints (task 63, contract §6.3 / ADR-032 appendix
 * B): login is required, every contract field is present at its documented path and type, and the
 * finance-private notes never reach the creator.
 */
class KolWithdrawalControllerTest {
  private static final String BASE = "/kol-api/withdrawals";
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  private final WithdrawalService withdrawals = mock(WithdrawalService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc =
      KolMvcSupport.build(current, new KolWithdrawalController(withdrawals, current));

  @Test
  void shouldExposeConditions_withNumbersForVersionAndCount() throws Exception {
    when(withdrawals.conditions(9L, 22L))
        .thenReturn(
            new Conditions(
                "CNY", "200.00", 4, "300.00", "100.00", "50.00", "800.00", false, 3L, true));
    mvc.perform(get(BASE + "/conditions").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currency").value("CNY"))
        .andExpect(jsonPath("$.data.minimum").value("200.00"))
        .andExpect(jsonPath("$.data.version").value(4))
        .andExpect(jsonPath("$.data.available").value("300.00"))
        .andExpect(jsonPath("$.data.occupied").value("100.00"))
        .andExpect(jsonPath("$.data.frozen").value("50.00"))
        .andExpect(jsonPath("$.data.estimated").value("800.00"))
        .andExpect(jsonPath("$.data.withdrawalFrozen").value(false))
        .andExpect(jsonPath("$.data.unverifiedCount").value(3))
        .andExpect(jsonPath("$.data.canApply").value(true));
  }

  @Test
  void shouldListRecipientOptions_withContractFieldNames() throws Exception {
    when(withdrawals.recipientOptions(9L, 22L))
        .thenReturn(
            List.of(new RecipientOption(31L, "PAYPAL", "cr@example.com", "Creator", "USD", null)));
    mvc.perform(get(BASE + "/recipient-options").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("31"))
        .andExpect(jsonPath("$.data[0].type").value("PAYPAL"))
        .andExpect(jsonPath("$.data[0].account").value("cr@example.com"))
        .andExpect(jsonPath("$.data[0].holder").value("Creator"))
        .andExpect(jsonPath("$.data[0].currency").value("USD"))
        .andExpect(jsonPath("$.data[0].network").doesNotExist());
  }

  @Test
  void shouldListOwnWithdrawals_withContractRowFieldsAndNumericTotal() throws Exception {
    when(withdrawals.pageKol(9L, 22L, 1, 20))
        .thenReturn(new PageResult<>(List.of(row()), 1L, 1, 20));
    mvc.perform(get(BASE).header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value("77"))
        .andExpect(jsonPath("$.data.items[0].applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.items[0].amount").value("400.00"))
        .andExpect(jsonPath("$.data.items[0].currency").value("CNY"))
        .andExpect(jsonPath("$.data.items[0].status").value("REJECTED"))
        .andExpect(jsonPath("$.data.items[0].createdAt").exists())
        .andExpect(jsonPath("$.data.items[0].reviewNote").value("not eligible"))
        .andExpect(jsonPath("$.data.items[0].rejectReason").value("not eligible"))
        .andExpect(jsonPath("$.data.items[0].account.id").value("31"))
        .andExpect(jsonPath("$.data.items[0].account.type").value("PAYPAL"))
        .andExpect(jsonPath("$.data.items[0].account.account").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.items[0].account.holder").value("Cr****"))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.items[0].amountCny").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].account.accountId").doesNotExist());
  }

  @Test
  void shouldReturnOwnDetail_withoutFinancePrivateFields() throws Exception {
    when(withdrawals.kolDetail(9L, 22L, 77L)).thenReturn(detail());
    mvc.perform(get(BASE + "/77").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("77"))
        .andExpect(jsonPath("$.data.applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.amount").value("400.00"))
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.note").value("please pay"))
        .andExpect(jsonPath("$.data.reviewNote").value("ok"))
        .andExpect(jsonPath("$.data.account.account").value("cr***@example.com"))
        .andExpect(jsonPath("$.data.history[0].action").value("SUBMITTED"))
        .andExpect(jsonPath("$.data.history[0].at").exists())
        .andExpect(jsonPath("$.data.history[0].actor").value("Creator"))
        .andExpect(jsonPath("$.data.history[0].note").value("please pay"))
        .andExpect(jsonPath("$.data.history[0].actorId").doesNotExist())
        .andExpect(jsonPath("$.data.payment.amountCny").value("400.00"))
        .andExpect(jsonPath("$.data.payment.actualAmount").value("400.00"))
        .andExpect(jsonPath("$.data.payment.actualCurrency").value("CNY"))
        .andExpect(jsonPath("$.data.payment.paidAt").exists())
        .andExpect(jsonPath("$.data.payment.method").value("bank"))
        .andExpect(jsonPath("$.data.payment.voucher").value("881"))
        .andExpect(jsonPath("$.data.financeNote").doesNotExist())
        .andExpect(jsonPath("$.data.payment.financeNote").doesNotExist());
  }

  @Test
  void shouldApply_withContractRequestFieldNames() throws Exception {
    when(withdrawals.apply(any(), any())).thenReturn(detail());
    String key = "7565e681-dfd4-48bb-9f83-82e66cbb04ad";
    mvc.perform(
            post(BASE)
                .header(KolMvcSupport.TOKEN_HEADER, token())
                .contentType("application/json")
                .content(
                    "{\"idempotencyKey\":\""
                        + key
                        + "\",\"amount\":\"400.00\","
                        + "\"payoutAccountId\":31,\"previousId\":55,\"note\":\"please pay\","
                        + "\"account\":{\"type\":\"PAYPAL\",\"account\":\"cr@example.com\","
                        + "\"holder\":\"Creator\",\"currency\":\"USD\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.applicationNo").value("W20261025000001"))
        .andExpect(jsonPath("$.data.amount").value("400.00"));
    ArgumentCaptor<ApplyCommand> command = ArgumentCaptor.forClass(ApplyCommand.class);
    verify(withdrawals).apply(any(), command.capture());
    assertThat(command.getValue().idempotencyKey()).isEqualTo(key);
    assertThat(command.getValue().amountCny()).isEqualTo("400.00");
    assertThat(command.getValue().payoutAccountId()).isEqualTo(31L);
    assertThat(command.getValue().previousId()).isEqualTo(55L);
    assertThat(command.getValue().note()).isEqualTo("please pay");
    assertThat(command.getValue().account().type()).isEqualTo("PAYPAL");
    assertThat(command.getValue().account().account()).isEqualTo("cr@example.com");
    assertThat(command.getValue().account().holder()).isEqualTo("Creator");
    assertThat(command.getValue().account().currency()).isEqualTo("USD");
  }

  @Test
  void shouldWithdrawOwnPendingApplication() throws Exception {
    when(withdrawals.withdraw(any(), eq(77L))).thenReturn(detail());
    mvc.perform(put(BASE + "/77/withdraw").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
    verify(withdrawals).withdraw(any(), eq(77L));
  }

  @Test
  void shouldRequireKolLoginBeforeAnyRead() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/conditions")).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/recipient-options")).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/77")).andExpect(status().isUnauthorized());
    verify(withdrawals, never())
        .pageKol(
            anyLong(),
            anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
    verify(withdrawals, never()).conditions(anyLong(), anyLong());
    verify(withdrawals, never()).kolDetail(anyLong(), anyLong(), anyLong());
  }

  private String token() {
    return KolMvcSupport.actAs(current, kol());
  }

  private KolAccountDTO kol() {
    return new KolAccountDTO(
        22L,
        "KOL-test",
        "Creator",
        "creator@example.invalid",
        "US",
        null,
        1,
        1,
        0,
        null,
        9L,
        "Tenant",
        true);
  }

  private static KolRow row() {
    return new KolRow(
        77L,
        "W20261025000001",
        "400.00",
        "CNY",
        "REJECTED",
        NOW,
        "not eligible",
        "not eligible",
        null,
        null,
        null,
        new AccountView(31L, "PAYPAL", "cr***@example.com", "Cr****", "USD", null, null));
  }

  private static Detail detail() {
    return new Detail(
        77L,
        "W20261025000001",
        22L,
        "KOL-test",
        "Creator",
        null,
        null,
        "COMMISSION",
        "400.00",
        "CNY",
        "PAID",
        false,
        null,
        NOW,
        "please pay",
        "ok",
        null,
        null,
        null,
        new AccountView(31L, "PAYPAL", "cr***@example.com", "Cr****", "USD", null, null),
        List.of(new HistoryItem("SUBMITTED", 22L, "Creator", "KOL", "please pay", NOW)),
        new PaymentView(
            "400.00",
            "400.00",
            "CNY",
            NOW,
            "bank",
            "internal finance note",
            null,
            881L,
            "Fin",
            "PAYMENT"));
  }
}
