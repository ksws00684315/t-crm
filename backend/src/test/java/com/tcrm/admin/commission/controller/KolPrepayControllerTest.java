package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.commission.service.PrepayService.Deduction;
import com.tcrm.admin.commission.service.PrepayService.LedgerRow;
import com.tcrm.admin.commission.service.PrepayService.PaidPrepay;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;

class KolPrepayControllerTest {
  private static final String BASE = "/kol-api/prepays";
  private static final Instant PAID_AT = Instant.parse("2026-10-22T04:00:00Z");
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final PrepayService prepays = mock(PrepayService.class);
  private final MockMvc mvc =
      KolMvcSupport.build(current, new KolPrepayController(prepays, current));

  @BeforeEach
  void prepare() {
    when(prepays.listPaid(11L, 22L, 1, 20))
        .thenReturn(new PageResult<>(List.of(paid(false, false)), 1L, 1, 20));
    when(prepays.paid(eq(11L), eq(22L), eq(77L))).thenReturn(paid(false, false));
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,false"})
  void shouldExposeOnlyPaidOwnResultAndRecoveryWithoutFinancePrivateFields(
      boolean deduct, boolean ledgerExists) throws Exception {
    when(prepays.paid(11L, 22L, 77L)).thenReturn(paid(deduct, ledgerExists));
    var result =
        mvc.perform(get(BASE + "/77").header(KolMvcSupport.TOKEN_HEADER, token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("77"))
            .andExpect(jsonPath("$.data.status").value("PAID"))
            .andExpect(jsonPath("$.data.payAmountCny").value("300.00"))
            .andExpect(jsonPath("$.data.reason").doesNotExist())
            .andExpect(jsonPath("$.data.payoutAccountSnapshot").doesNotExist())
            .andExpect(jsonPath("$.data.applicantId").doesNotExist());
    if (ledgerExists) {
      result
          .andExpect(jsonPath("$.data.deduction.remainingAmountCny").value("200.00"))
          .andExpect(jsonPath("$.data.walletBalanceCny").value("-200.00"));
    } else {
      result
          .andExpect(jsonPath("$.data.deduction").doesNotExist())
          .andExpect(jsonPath("$.data.walletBalanceCny").value("0.00"));
    }
  }

  @Test
  void shouldListPaidTasksWithDefaultPageAndCompanyBoundFromLogin() throws Exception {
    mvc.perform(get(BASE).header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.items[0].prepayNo").value("PP-test"));
    verify(prepays).listPaid(11L, 22L, 1, 20);
  }

  @Test
  void shouldReturnNotFoundForForeignMissingOrNonPaidTask() throws Exception {
    when(prepays.paid(11L, 22L, 77L)).thenThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND));
    mvc.perform(get(BASE + "/77").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldProjectPaidAndDeductionLedgerWithStablePagination(boolean balancePresent)
      throws Exception {
    when(prepays.ledger(11L, 22L, 2, 10))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new LedgerRow(
                        99L,
                        77L,
                        "PP-test",
                        "PREPAY_DEDUCT",
                        "-300.00",
                        PAID_AT,
                        balancePresent ? "-200.00" : null)),
                0L,
                2,
                10));
    var result =
        mvc.perform(
                get(BASE + "/ledger")
                    .param("page", "2")
                    .param("size", "10")
                    .header(KolMvcSupport.TOKEN_HEADER, token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value("0"))
            .andExpect(jsonPath("$.data.items[0].entryId").value("99"))
            .andExpect(jsonPath("$.data.items[0].amountCny").value("-300.00"));
    if (balancePresent) {
      result.andExpect(jsonPath("$.data.items[0].balanceAfterCny").value("-200.00"));
    } else {
      result.andExpect(jsonPath("$.data.items[0].balanceAfterCny").doesNotExist());
    }
    verify(prepays).ledger(11L, 22L, 2, 10);
  }

  @ParameterizedTest
  @ValueSource(strings = {"?page=0", "?size=0", "?size=101"})
  void shouldRejectInvalidPaginationForBothLists(String query) throws Exception {
    String token = token();
    mvc.perform(get(BASE + query).header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    mvc.perform(get(BASE + "/ledger" + query).header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    verify(prepays, never()).listPaid(anyLong(), anyLong(), anyInt(), anyInt());
    verify(prepays, never()).ledger(anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void shouldRequireKolLoginBeforeAnyDatabaseRead() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    verify(prepays, never()).listPaid(anyLong(), anyLong(), anyInt(), anyInt());
  }

  private static PaidPrepay paid(boolean deduct, boolean ledgerExists) {
    Deduction deduction =
        deduct && ledgerExists ? new Deduction("300.00", "100.00", "200.00") : null;
    return new PaidPrepay(
        77L,
        "PP-test",
        "PAID",
        "300.00",
        "CNY",
        PAID_AT,
        "BANK",
        88L,
        deduct,
        deduction,
        ledgerExists ? "-200.00" : "0.00");
  }

  private String token() {
    return KolMvcSupport.actAs(
        current,
        new KolAccountDTO(
            22L,
            "KOL-test",
            "Test",
            "test@example.invalid",
            "US",
            null,
            1,
            1,
            0,
            null,
            11L,
            "Test company",
            true));
  }
}
