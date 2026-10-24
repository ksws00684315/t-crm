package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Controller slice for the KOL-portal withdrawal endpoints (task 63 §12.01/12.02). */
class KolWithdrawalControllerTest {
  private static final String BASE = "/kol-api/withdrawals";
  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  private final WithdrawalService withdrawals = mock(WithdrawalService.class);
  private final BizFileService files = mock(BizFileService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc =
      KolMvcSupport.build(current, new KolWithdrawalController(withdrawals, files, current));

  private String token() {
    return KolMvcSupport.actAs(current, kol());
  }

  @Test
  void shouldShowConditionsWithAvailability() throws Exception {
    when(withdrawals.conditions(2L, 9007199254740993L))
        .thenReturn(
            new WithdrawalService.Conditions(
                "CNY", "200.00", "120.00", "30.00", "350.00", "320.00", false, true));
    mvc.perform(get(BASE + "/conditions").header("X-Kol-Token", token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availableCny").value("320.00"))
        .andExpect(jsonPath("$.data.minimumWithdrawalCny").value("200.00"))
        .andExpect(jsonPath("$.data.eligible").value(true));
  }

  @Test
  void shouldApplyWithInlinePayoutInfo() throws Exception {
    when(withdrawals.apply(any(), any())).thenReturn(detail());
    mvc.perform(
            post(BASE)
                .header("X-Kol-Token", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                {
                  "idempotencyKey": "7565e681-dfd4-48bb-9f83-82e66cbb04ad",
                  "amountCny": "400.00",
                  "account": {
                    "type": "PAYPAL",
                    "account": "creator@example.com",
                    "holder": "Creator Name",
                    "currency": "USD"
                  },
                  "note": "first"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.applicationNo").value("CW20261025000001"));
    verify(withdrawals).apply(any(), any());
  }

  @Test
  void shouldRejectMalformedAmount() throws Exception {
    mvc.perform(
            post(BASE)
                .header("X-Kol-Token", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                {
                  "idempotencyKey": "7565e681-dfd4-48bb-9f83-82e66cbb04ad",
                  "amountCny": "-5",
                  "account": {
                    "type": "PAYPAL",
                    "account": "creator@example.com",
                    "holder": "Creator Name",
                    "currency": "USD"
                  }
                }
                """))
        .andExpect(status().isBadRequest());
    verify(withdrawals, org.mockito.Mockito.never()).apply(any(), any());
  }

  @Test
  void shouldPageOwnApplicationsWithRejectReason() throws Exception {
    when(withdrawals.pageKol(2L, 9007199254740993L, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new WithdrawalService.KolRow(
                        77L,
                        "CW20261025000001",
                        "400.00",
                        "CNY",
                        "REJECTED",
                        NOW,
                        "wrong account",
                        null,
                        null,
                        null,
                        new WithdrawalService.AccountView(
                            null,
                            "PAYPAL",
                            "creator@example.com",
                            "Creator Name",
                            "USD",
                            null,
                            null))),
                1L,
                1,
                20));
    mvc.perform(get(BASE).header("X-Kol-Token", token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].status").value("REJECTED"))
        .andExpect(jsonPath("$.data.items[0].rejectReason").value("wrong account"))
        .andExpect(jsonPath("$.data.items[0].account.account").value("creator@example.com"));
  }

  @Test
  void shouldWithdrawOwnApplication() throws Exception {
    when(withdrawals.withdraw(any(), eq(77L))).thenReturn(detail());
    mvc.perform(put(BASE + "/77/withdraw").header("X-Kol-Token", token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    verify(withdrawals).withdraw(any(), eq(77L));
  }

  @Test
  void shouldRefuseAnonymousAccess() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        9007199254740993L,
        "KOL0001",
        "Alice",
        "alice@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        2L,
        "Acme",
        true);
  }

  private static WithdrawalService.Detail detail() {
    return new WithdrawalService.Detail(
        77L,
        "CW20261025000001",
        9007199254740993L,
        "KOL0001",
        "Alice",
        null,
        null,
        "COMMISSION_WITHDRAWAL",
        "400.00",
        "CNY",
        "PENDING_REVIEW",
        NOW,
        "first",
        null,
        null,
        null,
        1L,
        new WithdrawalService.AccountView(
            null, "PAYPAL", "creator@example.com", "Creator Name", "USD", null, null),
        List.of(),
        null);
  }
}
