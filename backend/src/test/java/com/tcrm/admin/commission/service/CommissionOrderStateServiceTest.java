package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the creator-portal commission line view (task 62, appendix B). */
class CommissionOrderStateServiceTest {
  private static final long KOL = 22L;

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final CommissionOrderStateService service =
      new CommissionOrderStateService(states, revisions);

  @BeforeEach
  void prepare() {
    lenient().when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of());
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
  void shouldMapReleasedRowToWithdrawable() {
    CommissionItemStateDO row = state(9002L, 5002L, KOL, "10.00", "10.00", true);
    when(states.listByOrderIds(List.of(5002L))).thenReturn(List.of(row));

    var result = service.listByKolAndOrderIds(KOL, List.of(5002L));

    assertThat(result.getFirst().settlementStatus())
        .isEqualTo(CommissionOrderStateApi.STATUS_WITHDRAWABLE);
    assertThat(result.getFirst().releasedAmount()).isEqualByComparingTo("10.00");
  }

  @Test
  void shouldMapUnverifiedAndRefundedOutRowsToInvalidWithNullAmounts() {
    CommissionItemStateDO unverified = state(9003L, 5003L, KOL, "0.00", "0.00", false);
    CommissionItemStateDO refundedOut = state(9004L, 5004L, KOL, "0.00", "0.00", true);
    when(states.listByOrderItemIds(List.of(9003L, 9004L)))
        .thenReturn(List.of(unverified, refundedOut));

    var result = service.listByOrderItemIds(KOL, List.of(9003L, 9004L));

    assertThat(result)
        .extracting(CommissionOrderStateApi.ItemCommissionStateDTO::settlementStatus)
        .containsExactly(
            CommissionOrderStateApi.STATUS_INVALID, CommissionOrderStateApi.STATUS_INVALID);
    assertThat(result.get(0).commissionAmount()).isNull();
    assertThat(result.get(0).releasedAmount()).isNull();
    assertThat(result.get(1).commissionAmount()).isZero();
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
