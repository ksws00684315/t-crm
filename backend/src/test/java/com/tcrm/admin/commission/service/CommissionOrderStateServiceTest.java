package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the creator-portal commission line view (task 62, appendix B). */
class CommissionOrderStateServiceTest {
  private static final long KOL = 22L;

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final CommissionOrderStateService service =
      new CommissionOrderStateService(states, revisions, withdrawals);

  @BeforeEach
  void prepare() {
    lenient().when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of());
    lenient().when(withdrawals.listOccupationsByKol(anyLong())).thenReturn(List.of());
  }

  @Test
  void shouldMapVerifiedUnreleasedRowToFrozenWithRate() {
    CommissionItemStateDO row = state(9001L, 5001L, KOL, "10.00", "0.00", true);
    when(states.listByOrderItemIds(List.of(9001L))).thenReturn(List.of(row));
    when(revisions.listLatestByItemStateIds(List.of(row.getId())))
        .thenReturn(List.of(revision(row.getId(), "0.1000")));

    var result = service.listByOrderItemIds(KOL, List.of(9001L));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().settlementStatus())
        .isEqualTo(CommissionOrderStateApi.STATUS_FROZEN);
    assertThat(result.getFirst().commissionAmount()).isEqualByComparingTo("10.00");
    assertThat(result.getFirst().releasedAmount()).isEqualByComparingTo("0.00");
    assertThat(result.getFirst().rateSnapshot()).isEqualByComparingTo("0.1000");
  }

  @Test
  void shouldMapPaidButUnsettledLineToFrozenInsteadOfInvalid() {
    // Task 81 (L-031): a paid order accrues from its pay amount before the settlement arrives, so
    // the creator sees FROZEN with the amount. Zero accrual is NO_COMMISSION, not INVALID.
    CommissionItemStateDO paid = state(9005L, 5005L, KOL, "4.33", "0.00", true);
    CommissionItemStateDO zeroAccrual = state(9006L, 5006L, KOL, "0.00", "0.00", true);
    when(states.listByOrderItemIds(List.of(9005L, 9006L))).thenReturn(List.of(paid, zeroAccrual));
    when(revisions.listLatestByItemStateIds(List.of(paid.getId(), zeroAccrual.getId())))
        .thenReturn(List.of(revision(paid.getId(), "0.1000")));

    var result = service.listByOrderItemIds(KOL, List.of(9005L, 9006L));

    assertThat(result)
        .extracting(CommissionOrderStateApi.ItemCommissionStateDTO::settlementStatus)
        .containsExactly(
            CommissionOrderStateApi.STATUS_FROZEN, CommissionOrderStateApi.STATUS_NO_COMMISSION);
    assertThat(result.get(0).commissionAmount()).isEqualByComparingTo("4.33");
    assertThat(result.get(0).releasedAmount()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldKeepAfterSalesRowFrozenEvenAfterRelease() {
    // Task 81 PR review (2026-10-30): an unconcluded refund blocks withdrawal, so a line released
    // before the refund opened must not show WITHDRAWABLE.
    CommissionItemStateDO released = state(9007L, 5007L, KOL, "10.00", "10.00", true);
    released.setAfterSales(true);
    when(states.listByOrderItemIds(List.of(9007L))).thenReturn(List.of(released));
    when(revisions.listLatestByItemStateIds(List.of(released.getId())))
        .thenReturn(List.of(revision(released.getId(), "0.1000")));

    var result = service.listByOrderItemIds(KOL, List.of(9007L));

    assertThat(result.getFirst().settlementStatus())
        .isEqualTo(CommissionOrderStateApi.STATUS_FROZEN);
  }

  @Test
  void shouldMapReleasedRowToWithdrawable() {
    CommissionItemStateDO row = state(9002L, 5002L, KOL, "10.00", "10.00", true);
    when(states.listByOrderIds(List.of(5002L))).thenReturn(List.of(row));

    var result = service.listByKolAndOrderIds(KOL, List.of(5002L));

    assertThat(result.getFirst().settlementStatus())
        .isEqualTo(CommissionOrderStateApi.STATUS_WITHDRAWABLE);
    assertThat(result.getFirst().releasedAmount()).isEqualByComparingTo("10.00");
  }

  @Test
  void shouldMapUnverifiedRowToInvalidWithNullAmounts() {
    CommissionItemStateDO unverified = state(9003L, 5003L, KOL, "0.00", "0.00", false);
    when(states.listByOrderItemIds(List.of(9003L))).thenReturn(List.of(unverified));

    var result = service.listByOrderItemIds(KOL, List.of(9003L));

    assertThat(result.getFirst().settlementStatus())
        .isEqualTo(CommissionOrderStateApi.STATUS_INVALID);
    assertThat(result.getFirst().commissionAmount()).isNull();
    assertThat(result.getFirst().releasedAmount()).isNull();
  }

  @Test
  void shouldMapVerifiedZeroCommissionRowToNoCommissionEvenIfReleasedBefore() {
    // Task 81 PR review (2026-10-30): a fully refunded order is verified with zero commission. It
    // is not pending verification, even when its line was released before the refund.
    CommissionItemStateDO refundedOut = state(9004L, 5004L, KOL, "0.00", "0.00", true);
    CommissionItemStateDO refundedAfterRelease = state(9008L, 5008L, KOL, "0.00", "6.50", true);
    refundedAfterRelease.setAfterSales(true);
    when(states.listByOrderItemIds(List.of(9004L, 9008L)))
        .thenReturn(List.of(refundedOut, refundedAfterRelease));

    var result = service.listByOrderItemIds(KOL, List.of(9004L, 9008L));

    assertThat(result)
        .extracting(CommissionOrderStateApi.ItemCommissionStateDTO::settlementStatus)
        .containsExactly(
            CommissionOrderStateApi.STATUS_NO_COMMISSION,
            CommissionOrderStateApi.STATUS_NO_COMMISSION);
    assertThat(result.get(0).commissionAmount()).isZero();
    assertThat(result.get(1).releasedAmount()).isEqualByComparingTo("6.50");
  }

  @Test
  void shouldReturnOnlyTheCreatorLinesAndEmptyForNoIds() {
    CommissionItemStateDO foreign = state(9005L, 5005L, 99L, "10.00", "0.00", true);
    when(states.listByOrderItemIds(List.of(9005L))).thenReturn(List.of(foreign));

    assertThat(service.listByOrderItemIds(KOL, List.of(9005L))).isEmpty();
    assertThat(service.listByOrderItemIds(KOL, List.of())).isEmpty();
    assertThat(service.listByKolAndOrderIds(KOL, List.of())).isEmpty();
  }

  @Test
  void shouldListAllCreatorRowsForTheWorkbenchTotals() {
    when(states.listByKol(KOL))
        .thenReturn(
            List.of(
                state(9006L, 5006L, KOL, "10.00", "0.00", true),
                state(9007L, 5007L, KOL, "5.00", "5.00", true)));

    var result = service.listByKol(KOL);

    assertThat(result).hasSize(2);
    // Hand calc for the workbench: frozen = (10 - 0) + (5 - 5) = 10.
    BigDecimal frozen =
        result.stream()
            .map(line -> line.commissionAmount().subtract(line.releasedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(frozen).isEqualByComparingTo("10.00");
  }

  @Test
  void shouldMapActivelyOccupiedRow_toWithdrawing_overTheReleaseLadder() {
    // released > 0 would read WITHDRAWABLE, but an in-flight application occupies the line
    CommissionItemStateDO row = state(9003L, 5003L, KOL, "10.00", "10.00", true);
    when(states.listByOrderItemIds(List.of(9003L))).thenReturn(List.of(row));
    when(withdrawals.listOccupationsByKol(KOL))
        .thenReturn(List.of(occupation(9003L, "PENDING_PAYMENT")));
    var result = service.listByOrderItemIds(KOL, List.of(9003L));
    assertThat(result.getFirst().settlementStatus()).isEqualTo("WITHDRAWING");
  }

  @Test
  void shouldMapPaidOccupiedRow_toWithdrawn_evenWhenFrozen() {
    CommissionItemStateDO row = state(9004L, 5004L, KOL, "10.00", "0.00", true);
    when(states.listByOrderItemIds(List.of(9004L))).thenReturn(List.of(row));
    when(withdrawals.listOccupationsByKol(KOL)).thenReturn(List.of(occupation(9004L, "PAID")));
    var result = service.listByOrderItemIds(KOL, List.of(9004L));
    assertThat(result.getFirst().settlementStatus()).isEqualTo("WITHDRAWN");
  }

  @Test
  void shouldPreferWithdrawn_overWithdrawing_whenBothApplicationsTouchTheLine() {
    CommissionItemStateDO row = state(9005L, 5005L, KOL, "10.00", "10.00", true);
    when(states.listByOrderItemIds(List.of(9005L))).thenReturn(List.of(row));
    when(withdrawals.listOccupationsByKol(KOL))
        .thenReturn(List.of(occupation(9005L, "PENDING_REVIEW"), occupation(9005L, "PAID")));
    var result = service.listByOrderItemIds(KOL, List.of(9005L));
    assertThat(result.getFirst().settlementStatus()).isEqualTo("WITHDRAWN");
  }

  @Test
  void shouldIgnoreForeignOccupations_andKeepTheLadderForUnoccupiedLines() {
    CommissionItemStateDO row = state(9006L, 5006L, KOL, "10.00", "10.00", true);
    when(states.listByOrderItemIds(List.of(9006L))).thenReturn(List.of(row));
    // occupations belong to the same mapper query but a different item line
    when(withdrawals.listOccupationsByKol(KOL)).thenReturn(List.of(occupation(9999L, "PAID")));
    var result = service.listByOrderItemIds(KOL, List.of(9006L));
    assertThat(result.getFirst().settlementStatus()).isEqualTo("WITHDRAWABLE");
  }

  private static WithdrawalMapper.OccupationRow occupation(long orderItemId, String status) {
    return new WithdrawalMapper.OccupationRow(
        orderItemId,
        77L,
        "W1",
        status,
        new BigDecimal("1.00"),
        new BigDecimal("1.00"),
        BigDecimal.ZERO,
        Instant.EPOCH,
        null);
  }

  private static CommissionItemStateDO state(
      long itemId, long orderId, long kolId, String accrued, String released, boolean verified) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(7000L + itemId % 1000);
    row.setOrderId(orderId);
    row.setOrderItemId(itemId);
    row.setKolId(kolId);
    row.setVerified(verified);
    row.setAccruedCommissionCny(new BigDecimal(accrued));
    row.setReleasedCommissionCny(new BigDecimal(released));
    return row;
  }

  private static CommissionItemRevisionDO revision(long itemStateId, String rate) {
    CommissionItemRevisionDO revision = new CommissionItemRevisionDO();
    revision.setItemStateId(itemStateId);
    revision.setRate(new BigDecimal(rate));
    return revision;
  }
}
