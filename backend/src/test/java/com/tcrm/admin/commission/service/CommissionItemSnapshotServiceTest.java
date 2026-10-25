package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.api.CommissionItemApi.ItemSourceDTO;
import com.tcrm.admin.commission.api.CommissionItemApi.OrderSourceDTO;
import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionRuleVersionMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolPayTimeRateDTO;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests of the snapshot engine: revisions, wallet deltas and idempotency (design §8.3). */
class CommissionItemSnapshotServiceTest {
  private static final Instant PAY_TIME = Instant.parse("2026-10-10T08:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
  private static final long TENANT = 7L;
  private static final long KOL_A = 101L;
  private static final long KOL_B = 102L;
  private static final long PRODUCT = 51L;

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final CommissionRuleVersionMapper ruleVersions = mock(CommissionRuleVersionMapper.class);
  private final CommissionWalletService wallet = mock(CommissionWalletService.class);
  private final KolApi kols = mock(KolApi.class);
  private final com.tcrm.admin.shared.CurrentTenantResolver tenantResolver = () -> TENANT;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final CommissionItemSnapshotService service =
      new CommissionItemSnapshotService(
          states, revisions, ruleVersions, wallet, kols, tenantResolver, clock);

  @BeforeEach
  void prepare() {
    when(ruleVersions.findEffectiveAt(any())).thenReturn(null);
    when(kols.rateEffectiveAt(anyLong(), any())).thenReturn(null);
    when(states.deleteStaleByOrder(anyLong(), anyCollection(), any())).thenReturn(0);
  }

  @Test
  void shouldRegisterUnverifiedRowWithoutBasis() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    TenantScope.runCompany(
        TENANT, () -> service.syncOrderItems(order(), List.of(item(9001L, KOL_A, 1, null, null))));
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(row.capture());
    assertThat(row.getValue().getVerified()).isFalse();
    assertThat(row.getValue().getReleaseBasis())
        .isEqualTo(CommissionSettlementRules.RELEASE_UNVERIFIED);
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("0");
    verify(wallet, never())
        .postCommissionAdjustment(anyLong(), anyLong(), anyLong(), any(), any(), any());
    verify(revisions).insert(any(CommissionItemRevisionDO.class));
  }

  @Test
  void shouldSkipWhenSourceVersionUnchanged() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null))));
    ArgumentCaptor<CommissionItemStateDO> inserted =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(inserted.capture());
    when(states.lockByOrderItemId(9001L)).thenReturn(inserted.getValue());
    org.mockito.Mockito.clearInvocations(states, revisions);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null))));
    verify(states, never())
        .updateById(any(com.tcrm.admin.commission.model.CommissionItemStateDO.class));
    verify(revisions, never())
        .insert(any(com.tcrm.admin.commission.model.CommissionItemRevisionDO.class));
  }

  @Test
  void shouldAccrueFromPromotionConfigRateAndRecordVersion() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    whenRateAtPayTime(KOL_A, "0.1500", 4);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("200.00"), null))));
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(row.capture());
    // Hand calc: 200 × 0.15 = 30.00.
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("30.00");
    assertThat(row.getValue().getVerified()).isTrue();
    assertThat(row.getValue().getConfigVersion()).isEqualTo(4);
    verify(wallet, never()).postCommissionCredit(any());
  }

  @Test
  void shouldUsePromotionConfigRateEvenForProductLine() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    whenRateAtPayTime(KOL_A, "0.1500", 4);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), PRODUCT))));
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(row.capture());
    // Unified KOL rate (2026-10-25, ADR-032 §3): a product-linked line resolves only from the
    // pay-time promotion config; product-level rates no longer exist in the resolution.
    // Hand calc: 100 × 0.15 = 15.00, config version 4 recorded.
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("15.00");
    assertThat(row.getValue().getConfigVersion()).isEqualTo(4);
  }

  @Test
  void shouldPostNegativeAdjustmentWhenRefundCutsReleasedAmount() {
    CommissionItemStateDO existing = verifiedRow(9001L, KOL_A, "100.00", "10.00");
    existing.setSourceVersion("old");
    when(states.lockByOrderItemId(9001L)).thenReturn(existing);
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(),
                // Released 10.00 (100×0.10); source now says refund 50 → accrual (100−50)×0.10 =
                // 5.00.
                List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null)).stream()
                    .map(i -> withRefund(i, new BigDecimal("50.00")))
                    .toList()));
    verify(wallet)
        .postCommissionAdjustment(
            eq(TENANT),
            eq(KOL_A),
            eq(existing.getId()),
            any(String.class),
            eq(new BigDecimal("-5.00")),
            eq(NOW));
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).updateById(row.capture());
    assertThat(row.getValue().getReleasedCommissionCny()).isEqualByComparingTo("5.00");
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("5.00");
  }

  @Test
  void shouldClawBackFromPreviousKolOnOwnershipChange() {
    CommissionItemStateDO existing = verifiedRow(9001L, KOL_A, "100.00", "10.00");
    existing.setSourceVersion("old");
    when(states.lockByOrderItemId(9001L)).thenReturn(existing);
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    whenRateAtPayTime(KOL_B, "0.1000", 3);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_B, 2, new BigDecimal("100.00"), null))));
    // Previous owner KOL_A loses the released 10.00 to its wallet (10.02 clawback).
    verify(wallet)
        .postCommissionAdjustment(
            eq(TENANT),
            eq(KOL_A),
            eq(existing.getId()),
            any(String.class),
            eq(new BigDecimal("-10.00")),
            eq(NOW));
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).updateById(row.capture());
    assertThat(row.getValue().getKolId()).isEqualTo(KOL_B);
    assertThat(row.getValue().getReleasedCommissionCny()).isEqualByComparingTo("0");
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("10.00");
    verify(wallet, times(1))
        .postCommissionAdjustment(anyLong(), anyLong(), anyLong(), any(), any(), any());
  }

  @Test
  void shouldDeleteStaleRowsDroppedFromSync() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    TenantScope.runCompany(
        TENANT, () -> service.syncOrderItems(order(), List.of(item(9001L, KOL_A, 1, null, null))));
    verify(states).deleteStaleByOrder(eq(5001L), eq(List.of(9001L)), eq(NOW));
  }

  private void whenRateAtPayTime(long kolId, String rate, int version) {
    when(kols.rateEffectiveAt(kolId, PAY_TIME))
        .thenReturn(new KolPayTimeRateDTO(new BigDecimal(rate), version));
  }

  private static OrderSourceDTO order() {
    return new OrderSourceDTO(5001L, 30L, PAY_TIME);
  }

  private static ItemSourceDTO item(
      long itemId, Long kolId, int attributionType, BigDecimal basis, Long productId) {
    return new ItemSourceDTO(
        itemId,
        "SUB-" + itemId,
        kolId,
        attributionType,
        productId,
        basis,
        null,
        null,
        "UNKNOWN",
        null,
        null,
        false);
  }

  private static ItemSourceDTO withRefund(ItemSourceDTO item, BigDecimal refund) {
    return new ItemSourceDTO(
        item.orderItemId(),
        item.subOrderId(),
        item.kolId(),
        item.attributionType(),
        item.productId(),
        item.basisCny(),
        Boolean.FALSE,
        refund,
        item.settlementState(),
        item.settlementTime(),
        item.completedTime(),
        item.afterSales());
  }

  private static CommissionItemStateDO verifiedRow(
      long itemId, long kolId, String basis, String released) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(7001L);
    row.setTenantId(TENANT);
    row.setOrderId(5001L);
    row.setOrderItemId(itemId);
    row.setKolId(kolId);
    row.setSourceKey("ORDER_ITEM:" + itemId);
    row.setSettlementState("UNKNOWN");
    row.setVerified(true);
    row.setAccruedCommissionCny(new BigDecimal(released));
    row.setReleasedCommissionCny(new BigDecimal(released));
    row.setReleaseBasis(CommissionSettlementRules.RELEASE_UNSETTLED);
    return row;
  }
}
