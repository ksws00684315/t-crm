package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.ItemOccupation;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit coverage of the real settlement-side funding source (task 62 tables, ADR-032 §6). */
class ItemStateWithdrawalFundingSourceTest {
  private static final long KOL = 22L;

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final CommissionRuleConfigService ruleConfig = mock(CommissionRuleConfigService.class);
  private final ItemStateWithdrawalFundingSource source =
      new ItemStateWithdrawalFundingSource(states, withdrawals, ruleConfig);

  @BeforeEach
  void prepare() {
    when(ruleConfig.current())
        .thenReturn(
            new CommissionRuleConfigService.CurrentConfig(
                3, new BigDecimal("200.00"), "SETTLE_TIME", 7, null));
  }

  @Test
  void shouldReturnLeftoversAfterOccupation_withItemStateIds() {
    when(states.listReleasedForWithdrawal(anyLong(), any()))
        .thenReturn(List.of(state(701L, 101L, "500.00"), state(702L, 102L, "150.00")));
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList()))
        .thenReturn(List.of(new ItemOccupation(101L, new BigDecimal("100.00"))));
    List<FundingRow> rows = source.listReleasedRows(KOL);
    // item1: 500.00 released - 100.00 occupied = 400.00 leftover; item2: 150.00 untouched
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).orderItemId()).isEqualTo(101L);
    assertThat(rows.get(0).itemStateId()).isEqualTo(701L);
    assertThat(rows.get(0).releasedCny()).isEqualByComparingTo("400.00");
    assertThat(rows.get(1).releasedCny()).isEqualByComparingTo("150.00");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Long>> items = ArgumentCaptor.forClass((Class) List.class);
    verify(withdrawals).sumOccupiedByItem(eq(KOL), items.capture());
    assertThat(items.getValue()).containsExactly(101L, 102L);
  }

  @Test
  void shouldSkipRows_whoseReleasedMoneyIsFullyOccupied() {
    when(states.listReleasedForWithdrawal(anyLong(), any()))
        .thenReturn(List.of(state(701L, 101L, "250.00")));
    // 250.00 released, 250.00 already occupied (exactly) → nothing left to allocate
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList()))
        .thenReturn(List.of(new ItemOccupation(101L, new BigDecimal("250.00"))));
    assertThat(source.listReleasedRows(KOL)).isEmpty();
  }

  @Test
  void shouldSkipRows_whoseOccupationExceedsTheRelease() {
    // a clawback raced past the release: released 100.00 but 150.00 still occupied
    when(states.listReleasedForWithdrawal(anyLong(), any()))
        .thenReturn(List.of(state(701L, 101L, "100.00")));
    when(withdrawals.sumOccupiedByItem(anyLong(), anyList()))
        .thenReturn(List.of(new ItemOccupation(101L, new BigDecimal("150.00"))));
    assertThat(source.listReleasedRows(KOL)).isEmpty();
  }

  @Test
  void shouldQueryNothingFurther_whenNoReleasedRowsExist() {
    when(states.listReleasedForWithdrawal(anyLong(), any())).thenReturn(List.of());
    assertThat(source.listReleasedRows(KOL)).isEmpty();
    verify(withdrawals, never()).sumOccupiedByItem(anyLong(), anyList());
  }

  @Test
  void shouldReadFrozenEstimatedAndUnverified_fromTheAccrualAggregate() {
    when(states.aggregateByKol(anyCollection()))
        .thenReturn(
            List.of(
                new CommissionItemStateMapper.KolAccrualRow(
                    KOL, new BigDecimal("600.00"), new BigDecimal("120.00"), 4L)));
    assertThat(source.frozenCny(KOL)).isEqualByComparingTo("120.00");
    assertThat(source.estimatedCny(KOL)).isEqualByComparingTo("600.00");
    assertThat(source.unverifiedCount(KOL)).isEqualTo(4L);
  }

  @Test
  void shouldDefaultTheAggregates_whenTheCreatorHasNoCommissionRows() {
    when(states.aggregateByKol(anyCollection())).thenReturn(List.of());
    assertThat(source.frozenCny(KOL)).isEqualByComparingTo("0.00");
    assertThat(source.estimatedCny(KOL)).isEqualByComparingTo("0.00");
    assertThat(source.unverifiedCount(KOL)).isZero();
  }

  @Test
  void shouldReportTheSettlementConfigVersion_fromTheRuleConfig() {
    assertThat(source.configVersion(KOL)).isEqualTo(3);
  }

  @Test
  void shouldReportVersionZero_whileOnlyTheDefaultsApply() {
    when(ruleConfig.current())
        .thenReturn(
            new CommissionRuleConfigService.CurrentConfig(
                0, new BigDecimal("200.00"), "SETTLE_TIME", 7, null));
    assertThat(source.configVersion(KOL)).isZero();
  }

  private static CommissionItemStateDO state(long id, long orderItemId, String releasedCny) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(id);
    row.setOrderItemId(orderItemId);
    row.setKolId(KOL);
    row.setReleasedCommissionCny(new BigDecimal(releasedCny));
    return row;
  }
}
