package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.Map;
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
  private final WithdrawalPauseService pauses = mock(WithdrawalPauseService.class);
  private final KolApi kols = mock(KolApi.class);
  private final com.tcrm.admin.shared.CurrentTenantResolver tenantResolver = () -> TENANT;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final CommissionItemSnapshotService service =
      new CommissionItemSnapshotService(
          states, revisions, ruleVersions, wallet, pauses, kols, tenantResolver, clock);

  @BeforeEach
  void prepare() {
    when(ruleVersions.findEffectiveAt(any())).thenReturn(null);
    when(kols.rateEffectiveAt(anyLong(), any())).thenReturn(null);
    when(states.deleteStaleByOrder(anyLong(), anyCollection(), any())).thenReturn(0);
    when(states.findStaleIdsByOrder(anyLong(), anyCollection())).thenReturn(List.of());
    // No occupations anywhere: the block state then follows the after-sales flag alone.
    lenient().when(pauses.isItemBlocked(anyLong())).thenReturn(false);
    lenient().when(states.findIdsByOrder(anyLong())).thenReturn(List.of());
  }

  @Test
  void shouldUseSupplementedRateWithoutReleasing_whenMissingPayTimeRate() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    when(revisions.supplementedRate(TENANT, 9001L, KOL_A)).thenReturn(new BigDecimal("0.1250"));
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null))));
    var revision = ArgumentCaptor.forClass(CommissionItemRevisionDO.class);
    verify(revisions).insert(revision.capture());
    // 100 CNY * 12.5% = 12.50 CNY; existing settlement/freeze rules still apply.
    assertThat(revision.getValue().getRate()).isEqualByComparingTo("0.1250");
    assertThat(revision.getValue().getAccruedCny()).isEqualByComparingTo("12.50");
    verify(wallet, never())
        .postCommissionAdjustment(anyLong(), anyLong(), anyLong(), any(), any(), any());
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
  void shouldZeroUnreleasedAccrualInAfterSales_andRestoreWhenRefundIsRejected() {
    // Task 113: pay 100, rate 10%, nothing released.
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    assertThat(row.getAccruedCommissionCny()).isEqualByComparingTo("10.00");
    assertFiveLayers(row, "0.00", "0.00", "0.00");

    syncAfterSales(true);
    assertThat(row.getAccruedCommissionCny()).isEqualByComparingTo("0.00");
    assertThat(row.getReleaseBasis()).isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    assertThat(row.getVerified()).isTrue();
    assertFiveLayers(row, "0.00", "0.00", "0.00");

    syncAfterSales(false);
    assertThat(row.getAccruedCommissionCny()).isEqualByComparingTo("10.00");
    assertThat(row.getReleaseBasis()).isNotEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    assertFiveLayers(row, "0.00", "0.00", "0.00");
    verifyNoInteractions(wallet);
  }

  @Test
  void shouldKeepReleasedAccrualWithoutAnyLedgerEntryInAfterSales() {
    // Released 10.00, then a refund opens: the released money stays, frozen, no clawback.
    CommissionItemStateDO existing = verifiedRow(9001L, KOL_A, "100.00", "10.00");
    existing.setSourceVersion("old");
    when(states.lockByOrderItemId(9001L)).thenReturn(existing);
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    syncAfterSales(true);
    ArgumentCaptor<CommissionItemStateDO> row =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).updateById(row.capture());
    assertThat(row.getValue().getAccruedCommissionCny()).isEqualByComparingTo("10.00");
    assertThat(row.getValue().getReleasedCommissionCny()).isEqualByComparingTo("10.00");
    assertThat(row.getValue().getReleaseBasis())
        .isEqualTo(CommissionSettlementRules.RELEASE_AFTER_SALES);
    // Wallet still 10.00 (no entry): 累计 10 = 冻结 10 + 可提现 0 + 提现中 0 + 已提现 0.
    assertFiveLayers(row.getValue(), "10.00", "0.00", "0.00");
    verifyNoInteractions(wallet);
  }

  @Test
  void shouldTakeTheWalletNegativeOnlyWhenThePartialRefundSucceedsAfterWithdrawal() {
    // Task 113 §4.1: released 10.00 and withdrawn (paid 10.00, wallet 0.00). The refund opens:
    // nothing is clawed back. Refund 30 succeeds: accrual 7.00, −3.00 posted, wallet −3.00.
    CommissionItemStateDO existing = verifiedRow(9001L, KOL_A, "100.00", "10.00");
    existing.setSourceVersion("old");
    when(states.lockByOrderItemId(9001L)).thenReturn(existing);
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    assertFiveLayers(existing, "0.00", "0.00", "10.00");

    syncAfterSales(true);
    verifyNoInteractions(wallet);
    assertThat(existing.getAccruedCommissionCny()).isEqualByComparingTo("10.00");
    assertFiveLayers(existing, "0.00", "0.00", "10.00");

    syncRefund(new BigDecimal("30.00"));
    ArgumentCaptor<BigDecimal> delta = ArgumentCaptor.forClass(BigDecimal.class);
    verify(wallet)
        .postCommissionAdjustment(
            eq(TENANT),
            eq(KOL_A),
            eq(existing.getId()),
            any(String.class),
            delta.capture(),
            eq(NOW));
    BigDecimal balance = new BigDecimal("0.00").add(delta.getValue());
    assertThat(balance).isEqualByComparingTo("-3.00");
    assertThat(existing.getAccruedCommissionCny()).isEqualByComparingTo("7.00");
    // 累计 7 = 冻结 0 + 可提现 −3 + 提现中 0 + 已提现 10.
    assertFiveLayers(existing, "-3.00", "0.00", "10.00");
  }

  @Test
  void shouldDeductReleasedCommissionOnlyWhenThePartialRefundSucceeds() {
    // Released 10.00 → refund open (no entry) → refund 30 succeeds → 7.00, clawback −3.00.
    CommissionItemStateDO existing = verifiedRow(9001L, KOL_A, "100.00", "10.00");
    existing.setSourceVersion("old");
    when(states.lockByOrderItemId(9001L)).thenReturn(existing);
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    syncAfterSales(true);
    verifyNoInteractions(wallet);
    syncRefund(new BigDecimal("30.00"));
    verify(wallet)
        .postCommissionAdjustment(
            eq(TENANT),
            eq(KOL_A),
            eq(existing.getId()),
            any(String.class),
            eq(new BigDecimal("-3.00")),
            eq(NOW));
    assertThat(existing.getAccruedCommissionCny()).isEqualByComparingTo("7.00");
    assertThat(existing.getReleasedCommissionCny()).isEqualByComparingTo("7.00");
    assertFiveLayers(existing, "7.00", "0.00", "0.00");
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
  void shouldAppendRevisionWhenFingerprintReturnsToEarlierValue() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    String first = row.getSourceVersion();

    syncAfterSales(true);
    syncAfterSales(false);
    syncAfterSales(false);

    ArgumentCaptor<CommissionItemRevisionDO> appended =
        ArgumentCaptor.forClass(CommissionItemRevisionDO.class);
    verify(revisions, times(3)).insert(appended.capture());
    assertThat(appended.getAllValues())
        .extracting(CommissionItemRevisionDO::getSourceVersion)
        .doesNotHaveDuplicates()
        .endsWith(row.getSourceVersion());
    assertThat(row.getSourceVersion()).isNotEqualTo(first);
    assertThat(row.getSourceVersion().substring(0, 32)).isEqualTo(first.substring(0, 32));
  }

  @Test
  void shouldUseNewWalletKeyForEveryRepeatedDecrease() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    row.setId(7001L);
    row.setReleasedCommissionCny(new BigDecimal("10.00"));

    syncRefund(new BigDecimal("50.00"));
    syncRefund(null);
    row.setReleasedCommissionCny(new BigDecimal("10.00"));
    syncRefund(new BigDecimal("50.00"));
    syncRefund(new BigDecimal("50.00"));

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(wallet, times(2))
        .postCommissionAdjustment(
            eq(TENANT), eq(KOL_A), eq(7001L), keys.capture(), eq(new BigDecimal("-5.00")), eq(NOW));
    assertThat(keys.getAllValues()).doesNotHaveDuplicates();
  }

  @Test
  void shouldUseNewClawbackKeyWhenOwnershipFlipsBack() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    whenRateAtPayTime(KOL_B, "0.1000", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    row.setId(7001L);
    row.setReleasedCommissionCny(new BigDecimal("10.00"));

    syncOwner(KOL_B);
    syncOwner(KOL_A);
    row.setReleasedCommissionCny(new BigDecimal("10.00"));
    syncOwner(KOL_B);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(wallet, times(2))
        .postCommissionAdjustment(
            eq(TENANT),
            eq(KOL_A),
            eq(7001L),
            keys.capture(),
            eq(new BigDecimal("-10.00")),
            eq(NOW));
    assertThat(keys.getAllValues()).doesNotHaveDuplicates();
  }

  @Test
  void shouldNotAppendRevisionWhenAmountOnlyDiffersInScale() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    firstRevision(new BigDecimal("100.00"));

    // Order writes hold an unscaled zero, the settlement recompute reads 0.00 from the database.
    syncRefund(new BigDecimal("0.00"));
    org.mockito.Mockito.clearInvocations(revisions);
    syncRefund(BigDecimal.ZERO);
    syncLine(withRefund(item(9001L, KOL_A, 1, new BigDecimal("100"), null), BigDecimal.ZERO));

    verify(revisions, never()).insert(any(CommissionItemRevisionDO.class));
  }

  @Test
  void shouldTreatBareFingerprintOfExistingRowAsCurrentVersion() {
    whenRateAtPayTime(KOL_A, "0.1000", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    // Rows written before task 83 carry the bare fingerprint.
    row.setSourceVersion(row.getSourceVersion().substring(0, 32));
    String legacy = row.getSourceVersion();
    org.mockito.Mockito.clearInvocations(states, revisions);

    syncAfterSales(false);
    verify(revisions, never()).insert(any(CommissionItemRevisionDO.class));

    syncAfterSales(true);
    assertThat(row.getSourceVersion()).isNotEqualTo(legacy).endsWith("#1");
  }

  @Test
  void shouldDeleteStaleRowsDroppedFromSync() {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    TenantScope.runCompany(
        TENANT, () -> service.syncOrderItems(order(), List.of(item(9001L, KOL_A, 1, null, null))));
    verify(states).deleteStaleByOrder(eq(5001L), eq(List.of(9001L)), eq(NOW));
  }

  @Test
  void shouldPauseWithdrawalsWhenLineEntersAfterSales_andResumeWhenItLeaves() {
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));

    // The predicate is read before the revision (unblocked) and after the write (blocked).
    when(pauses.isItemBlocked(row.getId())).thenReturn(false, true);
    syncAfterSales(true);
    verify(pauses).pauseForItem(row.getId());
    verify(pauses, never()).resumeForItem(anyLong());

    // The pause wrote its flag, so the row now reads as blocked; only clearing it lifts.
    when(pauses.isItemBlocked(row.getId())).thenReturn(true, false);
    syncAfterSales(false);
    verify(pauses).resumeForItem(row.getId());
  }

  @Test
  void shouldPauseWhenOccupationsOutrunTheReleasedMoney_withoutAfterSales() {
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    CommissionItemStateDO row = firstRevision(new BigDecimal("100.00"));
    // The refund already concluded (after-sales false), but the clawback cut the release (15.00
    // → 0.00) below the occupation (15.00 held): the shared predicate reads unblocked before the
    // revision and blocked after it, and the holding application is paused — with the prelock
    // pass up front, ahead of the wallet write.
    when(states.findIdsByOrder(5001L)).thenReturn(List.of(row.getId()));
    when(pauses.isItemBlocked(row.getId())).thenReturn(false, true);
    // The line's money was released (the unit test has no release job): the clawback below now
    // cuts a real released amount, exactly like the refund chain on a live stack.
    row.setReleasedCommissionCny(new BigDecimal("15.00"));
    syncLine(
        withRefund(
            item(9001L, KOL_A, 1, new BigDecimal("100.00"), null), new BigDecimal("100.00")));
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(pauses, wallet);
    order.verify(pauses).prelockForItems(List.of(row.getId()));
    order
        .verify(wallet)
        .postCommissionAdjustment(anyLong(), anyLong(), anyLong(), any(), any(), any());
    order.verify(pauses).pauseForItem(row.getId());
    verify(pauses, never()).resumeForItem(anyLong());
  }

  @Test
  void shouldPrelockEveryApplicationOnce_beforeAnyWalletWriteOnMultiLineOrders() {
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    // Two released lines of one order, each already holding 15.00 of occupations: one sync
    // refunds both in full. The applications of BOTH lines must be locked in a single up-front
    // pass, before the first wallet write — per-line prelocking would take wallet → application
    // on line 2 and can deadlock with the payment registration.
    when(states.lockByOrderItemId(9001L)).thenReturn(releasedRow(7001L, 9001L));
    when(states.lockByOrderItemId(9002L)).thenReturn(releasedRow(7002L, 9002L));
    when(states.findIdsByOrder(5001L)).thenReturn(List.of(7001L, 7002L));
    when(pauses.isItemBlocked(7001L)).thenReturn(false, true);
    when(pauses.isItemBlocked(7002L)).thenReturn(false, true);
    var refunded =
        withRefund(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null), new BigDecimal("100.00"));
    var refunded2 =
        withRefund(item(9002L, KOL_A, 1, new BigDecimal("100.00"), null), new BigDecimal("100.00"));
    TenantScope.runCompany(
        TENANT, () -> service.syncOrderItems(order(), List.of(refunded, refunded2)));
    // The one prelock pass precedes the FIRST wallet write; with every application already
    // locked, the per-line wallet write and pause below can never interleave with a payment.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(pauses, wallet);
    order.verify(pauses).prelockForItems(List.of(7001L, 7002L));
    order
        .verify(wallet, org.mockito.Mockito.times(1))
        .postCommissionAdjustment(anyLong(), anyLong(), anyLong(), any(), any(), any());
    verify(pauses).pauseForItem(7001L);
    verify(pauses).pauseForItem(7002L);
    verify(pauses, org.mockito.Mockito.times(1)).prelockForItems(anyCollection());
  }

  @Test
  void shouldNotRepeatPauseLinkageWhenRepeatedSyncChangesNothing() {
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    firstRevision(new BigDecimal("100.00"));
    syncAfterSales(true);
    org.mockito.Mockito.clearInvocations(pauses);
    // Same fingerprint as the stored revision: the sync skips the line entirely, so no duplicate
    // PAUSED history or calls.
    syncAfterSales(true);
    verify(pauses, never()).pauseForItem(anyLong());
    verify(pauses, never()).resumeForItem(anyLong());
  }

  @Test
  void shouldResumePausesHeldByLinesDroppedFromAFullSync() {
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    firstRevision(new BigDecimal("100.00"));
    when(states.findStaleIdsByOrder(eq(5001L), anyCollection())).thenReturn(List.of(7001L));
    when(states.findIdsByOrder(5001L)).thenReturn(List.of(7001L));
    // The next sync only carries line 9002, so line 9001's snapshot goes stale and can no longer
    // block: the pause it held must lift, with the application locked in the up-front pass.
    syncLine(item(9002L, KOL_A, 1, new BigDecimal("50.00"), null));
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(pauses);
    order.verify(pauses).prelockForItems(List.of(7001L));
    order.verify(pauses).resumeForItem(7001L);
  }

  @Test
  void shouldPrelockSoftDeletedLineIdsSoRevivedOccupationsStaySafe() {
    // L-054: a soft-deleted snapshot still holds withdrawal occupations. findIdsByOrder must
    // return it (mapper no longer filters is_deleted) so the up-front prelock covers that
    // application before the revived line writes the wallet.
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    // 7999 = soft-deleted prior line of the same order, still occupying a holding application.
    when(states.findIdsByOrder(5001L)).thenReturn(List.of(7999L));
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(), List.of(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null))));
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(pauses, wallet);
    order.verify(pauses).prelockForItems(List.of(7999L));
    // New insert has no released money yet — wallet may be never called; prelock still ran first.
    verify(pauses).prelockForItems(List.of(7999L));
  }

  @Test
  void shouldRunPauseLinkageAfterInsertWithoutNullIdGuard() {
    // L-054: applyRevision always assigns an id before pause linkage; the always-true
    // row.getId() != null guard is gone — a new insert that flips to blocked still pauses.
    whenRateAtPayTime(KOL_A, "0.1500", 3);
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    // New insert: wasBlocked short-circuits on null id, so isItemBlocked is only read once after
    // write.
    when(pauses.isItemBlocked(anyLong())).thenReturn(true);
    TenantScope.runCompany(
        TENANT,
        () ->
            service.syncOrderItems(
                order(),
                List.of(
                    withAfterSales(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null), true))));
    ArgumentCaptor<CommissionItemStateDO> inserted =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(inserted.capture());
    verify(pauses).pauseForItem(inserted.getValue().getId());
  }

  /**
   * 累计佣金 = 冻结中 + 可提现 + 提现中 + 已提现 (design §4.8.2) for a creator holding only this line, computed
   * with the real {@link WithdrawalFundingSource} available / frozen-shortfall algorithm. The
   * allocatable amount follows the funding SQL: released, not in after-sales, minus every
   * occupation including paid ones.
   */
  private static void assertFiveLayers(
      CommissionItemStateDO row, String balance, String activeOccupied, String paid) {
    BigDecimal wallet = new BigDecimal(balance);
    BigDecimal occupied = new BigDecimal(activeOccupied);
    BigDecimal withdrawn = new BigDecimal(paid);
    BigDecimal accrued = row.getAccruedCommissionCny();
    BigDecimal released = row.getReleasedCommissionCny();
    BigDecimal allocatable =
        Boolean.TRUE.equals(row.getAfterSales())
            ? BigDecimal.ZERO.setScale(2)
            : released.subtract(occupied).subtract(withdrawn).max(BigDecimal.ZERO.setScale(2));
    WithdrawalFundingSource funding = mock(WithdrawalFundingSource.class, CALLS_REAL_METHODS);
    doReturn(Map.of(KOL_A, allocatable)).when(funding).allocatableByKol(any());
    BigDecimal available = funding.availableCny(KOL_A, wallet, occupied);
    BigDecimal frozen =
        accrued.subtract(released).add(funding.frozenShortfallCny(KOL_A, wallet, occupied));
    assertThat(frozen.add(available).add(occupied).add(withdrawn)).isEqualByComparingTo(accrued);
  }

  /** Registers line 9001 and makes later syncs of the test find the row it created. */
  private CommissionItemStateDO firstRevision(BigDecimal basis) {
    when(states.lockByOrderItemId(9001L)).thenReturn(null);
    TenantScope.runCompany(
        TENANT, () -> service.syncOrderItems(order(), List.of(item(9001L, KOL_A, 1, basis, null))));
    ArgumentCaptor<CommissionItemStateDO> inserted =
        ArgumentCaptor.forClass(CommissionItemStateDO.class);
    verify(states).insert(inserted.capture());
    when(states.lockByOrderItemId(9001L)).thenReturn(inserted.getValue());
    return inserted.getValue();
  }

  private void syncAfterSales(boolean afterSales) {
    syncLine(withAfterSales(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null), afterSales));
  }

  private void syncRefund(BigDecimal refund) {
    syncLine(withRefund(item(9001L, KOL_A, 1, new BigDecimal("100.00"), null), refund));
  }

  private void syncOwner(long kolId) {
    syncLine(item(9001L, kolId, 1, new BigDecimal("100.00"), null));
  }

  private void syncLine(ItemSourceDTO line) {
    TenantScope.runCompany(TENANT, () -> service.syncOrderItems(order(), List.of(line)));
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
        false,
        null);
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
        item.afterSales(),
        item.basisCheckReason());
  }

  private static ItemSourceDTO withAfterSales(ItemSourceDTO item, boolean afterSales) {
    return new ItemSourceDTO(
        item.orderItemId(),
        item.subOrderId(),
        item.kolId(),
        item.attributionType(),
        item.productId(),
        item.basisCny(),
        item.basisIncludesRefund(),
        item.successfulRefundCny(),
        item.settlementState(),
        item.settlementTime(),
        item.completedTime(),
        afterSales,
        item.basisCheckReason());
  }

  /** A stored, verified line that already released 15.00 (fixed ids for two-line tests). */
  private static CommissionItemStateDO releasedRow(long id, long itemId) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(id);
    row.setTenantId(TENANT);
    row.setOrderId(5001L);
    row.setOrderItemId(itemId);
    row.setKolId(KOL_A);
    row.setSourceKey("ORDER_ITEM:" + itemId);
    row.setSettlementState("UNKNOWN");
    row.setVerified(true);
    row.setAfterSales(false);
    row.setAccruedCommissionCny(new BigDecimal("15.00"));
    row.setReleasedCommissionCny(new BigDecimal("15.00"));
    row.setReleaseBasis(CommissionSettlementRules.RELEASE_UNSETTLED);
    return row;
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
