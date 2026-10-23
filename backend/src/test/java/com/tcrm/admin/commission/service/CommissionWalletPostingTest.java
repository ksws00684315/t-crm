package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CommissionWalletPostingTest {
  private static final Instant TIME = Instant.parse("2026-10-22T04:00:00Z");
  private final KolApi kols = mock(KolApi.class);
  private final List<Write> writes = new ArrayList<>();
  private List<ResultSet> existing = List.of();
  private List<ResultSet> outstanding = List.of();
  private List<ResultSet> balances = List.of();
  private final JdbcTemplate jdbc =
      mock(
          JdbcTemplate.class,
          invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 0 || !(args[0] instanceof String sql)) {
              return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            if (invocation.getMethod().getName().equals("update")) {
              writes.add(new Write(sql, Arrays.asList(args).subList(1, args.length)));
              return 1;
            }
            if (invocation.getMethod().getName().equals("queryForObject")) {
              return new BigDecimal("-300.00");
            }
            if (args[1] instanceof RowMapper<?> mapper) {
              List<ResultSet> data =
                  sql.contains("source_key =")
                      ? existing
                      : sql.contains("HAVING") ? outstanding : balances;
              List<Object> mapped = new ArrayList<>();
              for (int index = 0; index < data.size(); index++) {
                mapped.add(mapper.mapRow(data.get(index), index));
              }
              return mapped;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
          });
  private final CommissionWalletService service =
      new CommissionWalletService(
          jdbc, kols, mock(SystemConfigApi.class), Clock.fixed(TIME, ZoneOffset.UTC));

  @BeforeEach
  void prepare() {
    when(kols.listByIds(List.of(22L)))
        .thenReturn(List.of(new KolRefDTO(22L, "KOL-test", "Test", true, 5L)));
  }

  @Test
  void shouldAddCommissionAndAllocateAcrossOldestDeductionsUntilCreditIsExhausted()
      throws Exception {
    outstanding = List.of(deduction(1L, "100"), deduction(2L, "200"), deduction(3L, "50"));
    // -300 + 150 = -150; oldest debt recovers 100 and next debt recovers 50.
    assertThat(service.postCommissionCredit(command("150"))).isEqualByComparingTo("-150");
    var allocations =
        writes.stream()
            .filter(w -> w.sql().contains("INSERT INTO crm_commission_recovery"))
            .toList();
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).args()).contains(1L, new BigDecimal("100"));
    assertThat(allocations.get(1).args()).contains(2L, new BigDecimal("50.00"));
    assertThat(writes)
        .filteredOn(w -> w.sql().contains("INSERT INTO crm_commission_ledger"))
        .hasSize(1);
    assertThat(writes.getLast().args()).contains(new BigDecimal("-150.00"));
  }

  @Test
  void shouldLeaveExcessCommissionAvailableAfterAllPrepayDebtIsRecovered() throws Exception {
    outstanding = List.of(deduction(1L, "300"));
    // -300 + 450 = 150; recovered amount remains capped at 300.
    assertThat(service.postCommissionCredit(command("450"))).isEqualByComparingTo("150");
    assertThat(writes)
        .filteredOn(w -> w.sql().contains("crm_commission_recovery"))
        .singleElement()
        .satisfies(w -> assertThat(w.args()).contains(new BigDecimal("300")));
  }

  @Test
  void shouldReturnOriginalBalanceWithoutReposting_whenCreditIsRetried() throws Exception {
    existing = List.of(entry("150", "-150", 22L, "COMMISSION", 33L));
    assertThat(service.postCommissionCredit(command("150"))).isEqualByComparingTo("-150");
    assertThat(writes)
        .noneMatch(w -> w.sql().contains("crm_commission_ledger") || w.sql().startsWith("UPDATE"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"amount", "kol", "type", "source"})
  void shouldRejectConflictingSourceKeyWithoutUpdatingWallet(String mismatch) throws Exception {
    existing =
        List.of(
            entry(
                mismatch.equals("amount") ? "151" : "150",
                "-150",
                mismatch.equals("kol") ? 99L : 22L,
                mismatch.equals("type") ? "PREPAY" : "COMMISSION",
                mismatch.equals("source") ? 99L : 33L));
    fails(() -> service.postCommissionCredit(command("150")), ErrorCode.PREPAY_REQUEST_CONFLICT);
    assertThat(writes).noneMatch(w -> w.sql().startsWith("UPDATE"));
  }

  @Test
  void shouldDeductPrepayExactlyOnceAndReturnOriginalBalanceOnRetry() throws Exception {
    // -300 - 100 = -400; the second call does not deduct another 100.
    assertThat(service.postPrepayDeduction(11L, 22L, 33L, new BigDecimal("100"), TIME, 5L))
        .isEqualByComparingTo("-400");
    assertThat(writes)
        .filteredOn(w -> w.sql().contains("crm_commission_ledger"))
        .singleElement()
        .satisfies(w -> assertThat(w.args()).contains(new BigDecimal("-100"), "PREPAY_DEDUCT:33"));
    existing = List.of(entry("-100", "-400", 22L, "PREPAY", 33L));
    writes.clear();
    assertThat(service.postPrepayDeduction(11L, 22L, 33L, new BigDecimal("100"), TIME, 5L))
        .isEqualByComparingTo("-400");
    assertThat(writes)
        .noneMatch(w -> w.sql().contains("crm_commission_ledger") || w.sql().startsWith("UPDATE"));
  }

  @ParameterizedTest
  @MethodSource("invalidCredits")
  void shouldRejectInvalidCreditBeforeAnyDatabaseWrite(
      CommissionWalletService.CreditCommand command) {
    fails(() -> service.postCommissionCredit(command), ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  static Stream<Arguments> invalidCredits() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(
            new CommissionWalletService.CreditCommand(0, 22, 33, "key", new BigDecimal("1"), TIME)),
        Arguments.of(
            new CommissionWalletService.CreditCommand(11, 0, 33, "key", new BigDecimal("1"), TIME)),
        Arguments.of(
            new CommissionWalletService.CreditCommand(11, 22, 0, "key", new BigDecimal("1"), TIME)),
        Arguments.of(
            new CommissionWalletService.CreditCommand(11, 22, 33, null, new BigDecimal("1"), TIME)),
        Arguments.of(
            new CommissionWalletService.CreditCommand(11, 22, 33, " ", new BigDecimal("1"), TIME)),
        Arguments.of(
            new CommissionWalletService.CreditCommand(
                11, 22, 33, "x".repeat(121), new BigDecimal("1"), TIME)),
        Arguments.of(new CommissionWalletService.CreditCommand(11, 22, 33, "key", null, TIME)),
        Arguments.of(command("0")),
        Arguments.of(command("-1")),
        Arguments.of(command("0.001")),
        Arguments.of(command("1000000000000")),
        Arguments.of(
            new CommissionWalletService.CreditCommand(
                11, 22, 33, "key", new BigDecimal("1"), null)));
  }

  @ParameterizedTest
  @MethodSource("invalidDeductionAmounts")
  void shouldRejectInvalidDeductionBeforeDatabaseWrite(BigDecimal amount) {
    fails(
        () -> service.postPrepayDeduction(11, 22, 33, amount, TIME, 5),
        ErrorCode.COMMON_VALIDATION_FAILED);
    assertThat(writes).isEmpty();
  }

  static Stream<Arguments> invalidDeductionAmounts() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(BigDecimal.ZERO),
        Arguments.of(new BigDecimal("-1")),
        Arguments.of(new BigDecimal("1.001")));
  }

  @Test
  void shouldRejectCreditForMissingKol() {
    when(kols.listByIds(List.of(22L))).thenReturn(List.of());
    fails(() -> service.postCommissionCredit(command("100")), ErrorCode.COMMON_NOT_FOUND);
    assertThat(writes).isEmpty();
  }

  @Test
  void shouldReadZeroForMissingWalletAndRequireMinimumForWithdrawal() throws Exception {
    assertThat(service.currentBalance(11, 22)).isEqualByComparingTo("0.00");
    ResultSet rs = mock(ResultSet.class);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("200.00"));
    balances = List.of(rs);
    assertThat(service.currentBalance(11, 22)).isEqualByComparingTo("200");
    assertThat(service.balance(11, 22, new BigDecimal("200")).withdrawalEligible()).isTrue();
    assertThat(service.balance(11, 22, new BigDecimal("201")).withdrawalEligible()).isFalse();
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("-1.00"));
    assertThat(service.balance(11, 22, BigDecimal.ZERO).withdrawalEligible()).isFalse();
  }

  private static CommissionWalletService.CreditCommand command(String amount) {
    return new CommissionWalletService.CreditCommand(
        11, 22, 33, "t47_credit", new BigDecimal(amount), TIME);
  }

  private static ResultSet deduction(long id, String remaining) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getBigDecimal("remaining")).thenReturn(new BigDecimal(remaining));
    return rs;
  }

  private static ResultSet entry(String amount, String balance, long kol, String type, long source)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal(amount));
    when(rs.getBigDecimal(2)).thenReturn(new BigDecimal(balance));
    when(rs.getLong(3)).thenReturn(kol);
    when(rs.getString(4)).thenReturn(type);
    when(rs.getLong(5)).thenReturn(source);
    return rs;
  }

  private static void fails(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  private record Write(String sql, List<Object> args) {}
}
