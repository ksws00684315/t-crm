package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;

class KolPrepayControllerTest {
  private static final String BASE = "/kol-api/prepays";
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final ResultSet rs = mock(ResultSet.class);
  private final List<Read> reads = new ArrayList<>();
  private Long count = 1L;
  private final JdbcTemplate jdbc =
      mock(
          JdbcTemplate.class,
          invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 0 || !(args[0] instanceof String sql)) {
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            reads.add(new Read(sql, Arrays.asList(args).subList(2, args.length)));
            if (args[1] == Long.class) {
              return count;
            }
            if (args[1] == BigDecimal.class) {
              return new BigDecimal("100.00");
            }
            if (args[1] instanceof RowMapper<?> mapper) {
              Object mapped = mapper.mapRow(rs, 0);
              return invocation.getMethod().getName().equals("queryForObject")
                  ? mapped
                  : List.of(mapped);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
          });
  private final MockMvc mvc = KolMvcSupport.build(current, new KolPrepayController(jdbc, current));

  @BeforeEach
  void prepare() throws Exception {
    when(rs.getLong(1)).thenReturn(77L);
    when(rs.getLong("id")).thenReturn(77L);
    when(rs.getLong("voucher_file_id")).thenReturn(88L);
    when(rs.getLong("deduction_id")).thenReturn(99L);
    when(rs.getString("prepay_no")).thenReturn("PP-test");
    when(rs.getString("pay_method")).thenReturn("BANK");
    when(rs.getBigDecimal("pay_amount_cny")).thenReturn(new BigDecimal("300.00"));
    when(rs.getTimestamp("pay_time"))
        .thenReturn(Timestamp.from(Instant.parse("2026-10-22T04:00:00Z")));
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,false"})
  void shouldExposeOnlyPaidOwnResultAndRecoveryWithoutFinancePrivateFields(
      boolean deduct, boolean ledgerExists) throws Exception {
    when(rs.getObject("deduct_from_commission")).thenReturn(deduct);
    when(rs.getObject("deduction_id")).thenReturn(ledgerExists ? 99L : null);
    if (ledgerExists) {
      when(rs.getBigDecimal("balance_cny")).thenReturn(new BigDecimal("-200.00"));
    }
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
      // 300 paid - 100 recovered = 200 outstanding.
      result
          .andExpect(jsonPath("$.data.deduction.remainingAmountCny").value("200.00"))
          .andExpect(jsonPath("$.data.walletBalanceCny").value("-200.00"));
    } else {
      result
          .andExpect(jsonPath("$.data.deduction").doesNotExist())
          .andExpect(jsonPath("$.data.walletBalanceCny").value("0.00"));
    }
    assertThat(reads).allSatisfy(read -> assertThat(read.args()).contains(11L));
    assertThat(reads)
        .filteredOn(read -> read.sql().contains("FROM crm_prepay p"))
        .allSatisfy(
            read -> {
              assertThat(read.sql())
                  .contains("p.tenant_id = ? AND p.kol_id = ?", "p.status = 'PAID'");
              assertThat(read.args()).contains(11L, 22L, 77L);
            });
  }

  @Test
  void shouldListPaidTasksWithDefaultPageAndCompanyBoundFromLogin() throws Exception {
    mvc.perform(get(BASE).header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.items[0].prepayNo").value("PP-test"));
    assertThat(reads)
        .filteredOn(read -> read.sql().startsWith("SELECT id"))
        .singleElement()
        .satisfies(
            read -> {
              assertThat(read.sql())
                  .contains(
                      "tenant_id = ? AND kol_id = ? AND status = 'PAID'",
                      "ORDER BY create_time DESC, id DESC");
              assertThat(read.args()).containsExactly(11L, 22L, 20, 0L);
            });
  }

  @Test
  void shouldReturnNotFoundForForeignMissingOrNonPaidTask() throws Exception {
    count = 0L;
    String token = token();
    mvc.perform(get(BASE + "/77").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isNotFound());
    count = null;
    mvc.perform(get(BASE + "/77").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isNotFound());
    assertThat(reads).allMatch(read -> read.sql().startsWith("SELECT count(*)"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldProjectPaidAndDeductionLedgerWithStablePagination(boolean balancePresent)
      throws Exception {
    when(rs.getLong("entry_id")).thenReturn(99L);
    when(rs.getLong("prepay_id")).thenReturn(77L);
    when(rs.getString("entry_type")).thenReturn("PREPAY_DEDUCT");
    when(rs.getBigDecimal("amount_cny")).thenReturn(new BigDecimal("-300"));
    when(rs.getTimestamp("occurred_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-10-22T04:00:00Z")));
    if (balancePresent) {
      when(rs.getBigDecimal("balance_after_cny")).thenReturn(new BigDecimal("-200"));
    }
    count = null;
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
    assertThat(reads)
        .filteredOn(read -> read.sql().startsWith("SELECT entry_id"))
        .singleElement()
        .satisfies(read -> assertThat(read.args()).containsExactly(11L, 22L, 10, 10L));
  }

  @ParameterizedTest
  @ValueSource(strings = {"?page=0", "?size=0", "?size=101"})
  void shouldRejectInvalidPaginationForBothLists(String query) throws Exception {
    String token = token();
    mvc.perform(get(BASE + query).header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    mvc.perform(get(BASE + "/ledger" + query).header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    assertThat(reads).isEmpty();
  }

  @Test
  void shouldRequireKolLoginBeforeAnyDatabaseRead() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    assertThat(reads).isEmpty();
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

  private record Read(String sql, List<Object> args) {}
}
