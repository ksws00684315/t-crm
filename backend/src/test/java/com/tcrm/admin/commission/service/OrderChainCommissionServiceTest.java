package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper.ItemLedgerRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.AllocationOccupationRow;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit coverage of the commission side of the order chain (task 72, B07–B09). */
class OrderChainCommissionServiceTest {
  private static final Instant PAID = Instant.parse("2026-10-16T00:00:00Z");
  private static final Instant SETTLED = Instant.parse("2026-10-20T00:00:00Z");
  private static final Instant APPLIED = Instant.parse("2026-10-22T00:00:00Z");
  private static final Instant PAID_OUT = Instant.parse("2026-10-23T00:00:00Z");

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final CommissionWalletMapper wallet = mock(CommissionWalletMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final OrderChainCommissionService service =
      new OrderChainCommissionService(states, revisions, wallet, withdrawals);

  @Test
  void shouldSplitFlowsPerAllocation_andCountOnlyOccupyingApplications() {
    when(states.listByOrderIds(any())).thenReturn(List.of(state(50L, 20L, true, "10.00")));
    when(revisions.listLatestByItemStateIds(any())).thenReturn(List.of(revision(50L, "0.3000")));
    when(wallet.listByItemStates(any()))
        .thenReturn(
            List.of(new ItemLedgerRow(900L, "COMMISSION_CREDIT", dec("10.00"), SETTLED, 50L)));
    when(withdrawals.listAllocationsByOrderItems(any()))
        .thenReturn(
            List.of(
                // First partial withdrawal, paid: money flowed at PAID_OUT.
                alloc(801L, 20L, "6.00", 31L, "W20261022000001", "PAID", APPLIED, PAID_OUT),
                // Second partial withdrawal, in flight: no payment yet.
                alloc(
                    802L,
                    20L,
                    "3.00",
                    32L,
                    "W20261023000002",
                    "PENDING_PAYMENT",
                    APPLIED.plusSeconds(60),
                    null),
                // Rejected: released, so neither occupied nor a money flow.
                alloc(
                    803L,
                    20L,
                    "1.00",
                    33L,
                    "W20261023000003",
                    "REJECTED",
                    APPLIED.plusSeconds(120),
                    null)));

    var chain = service.chainOfOrder(10L, List.of(20L));

    var line = chain.lines().getFirst();
    assertThat(line.rateSnapshot()).isEqualByComparingTo("0.3000");
    assertThat(line.netCommissionCny()).isEqualByComparingTo("10.00");
    assertThat(line.releaseBasis()).isEqualTo("CONFIRMED");
    // 6.00 paid + 3.00 in flight; the rejected 1.00 is released.
    assertThat(line.occupiedCny()).isEqualByComparingTo("9.00");
    assertThat(line.withdrawals()).hasSize(3);
    assertThat(line.withdrawals().get(2).status()).isEqualTo("REJECTED");
    assertThat(chain.netCommissionCny()).isEqualByComparingTo("10.00");
    assertThat(chain.allVerified()).isTrue();
    assertThat(chain.flows()).hasSize(3);
    var credit = chain.flows().get(0);
    assertThat(credit.flowNo()).isEqualTo("CL900");
    assertThat(credit.direction()).isEqualTo("INCREASE");
    assertThat(credit.businessAt()).isEqualTo(SETTLED);
    assertThat(credit.transferredAt()).isNull();
    assertThat(credit.withdrawalNo()).isNull();
    var paid = chain.flows().get(1);
    assertThat(paid.flowNo()).isEqualTo("WA801");
    assertThat(paid.direction()).isEqualTo("DECREASE");
    assertThat(paid.amountCny()).isEqualByComparingTo("6.00");
    assertThat(paid.transferredAt()).isEqualTo(PAID_OUT);
    assertThat(paid.withdrawalNo()).isEqualTo("W20261022000001");
    var pending = chain.flows().get(2);
    assertThat(pending.flowNo()).isEqualTo("WA802");
    assertThat(pending.transferredAt()).isNull();
    assertThat(pending.withdrawalNo()).isEqualTo("W20261023000002");
  }

  @Test
  void shouldKeepRefundClawbackAsDecrease_andUnverifiedAsNull() {
    when(states.listByOrderIds(any()))
        .thenReturn(List.of(state(50L, 20L, true, "6.00"), unverified(51L, 21L)));
    when(revisions.listLatestByItemStateIds(any())).thenReturn(List.of(revision(50L, "0.3000")));
    // Refund clawback after a release: the sign decides the direction, never the flow type.
    when(wallet.listByItemStates(any()))
        .thenReturn(
            List.of(
                new ItemLedgerRow(900L, "COMMISSION_CREDIT", dec("10.00"), SETTLED, 50L),
                new ItemLedgerRow(
                    901L, "COMMISSION_ADJUSTMENT", dec("-4.00"), SETTLED.plusSeconds(3600), 50L)));
    when(withdrawals.listAllocationsByOrderItems(any())).thenReturn(List.of());

    var chain = service.chainOfOrder(10L, List.of(20L, 21L));

    var verified = chain.lines().get(0);
    assertThat(verified.netCommissionCny()).isEqualByComparingTo("6.00");
    var unverified = chain.lines().get(1);
    // Unknown stays null — distinct from the verified line's real amounts.
    assertThat(unverified.netCommissionCny()).isNull();
    assertThat(unverified.rateSnapshot()).isNull();
    assertThat(unverified.releaseBasis()).isEqualTo("UNVERIFIED");
    assertThat(unverified.occupiedCny()).isEqualByComparingTo("0.00");
    assertThat(unverified.withdrawals()).isEmpty();
    assertThat(chain.allVerified()).isFalse();
    // The order total sums live snapshots; the unverified row contributes its stored 0.
    assertThat(chain.netCommissionCny()).isEqualByComparingTo("6.00");
    var clawback =
        chain.flows().stream().filter(f -> f.flowNo().equals("CL901")).findFirst().orElseThrow();
    assertThat(clawback.direction()).isEqualTo("DECREASE");
    assertThat(clawback.amountCny()).isEqualByComparingTo("4.00");
    assertThat(clawback.businessAt()).isEqualTo(SETTLED.plusSeconds(3600));
  }

  @Test
  void shouldFallBackToEmptyBlocks_whenOrderHasNoItemsOrSnapshots() {
    var empty = service.chainOfOrder(10L, List.of());
    assertThat(empty.netCommissionCny()).isEqualByComparingTo("0.00");
    assertThat(empty.allVerified()).isTrue();
    assertThat(empty.lines()).isEmpty();
    assertThat(empty.flows()).isEmpty();

    when(states.listByOrderIds(any())).thenReturn(List.of());
    when(withdrawals.listAllocationsByOrderItems(any())).thenReturn(List.of());
    var chain = service.chainOfOrder(10L, List.of(20L));
    var line = chain.lines().getFirst();
    assertThat(line.orderItemId()).isEqualTo(20L);
    assertThat(line.rateSnapshot()).isNull();
    assertThat(line.netCommissionCny()).isNull();
    assertThat(line.releaseBasis()).isNull();
    assertThat(line.occupiedCny()).isEqualByComparingTo("0.00");
    assertThat(line.withdrawals()).isEmpty();
  }

  private static CommissionItemStateDO state(
      long id, long orderItemId, boolean verified, String accrued) {
    var row = new CommissionItemStateDO();
    row.setId(id);
    row.setOrderId(10L);
    row.setOrderItemId(orderItemId);
    row.setKolId(100L);
    row.setVerified(verified);
    row.setAccruedCommissionCny(dec(accrued));
    row.setReleasedCommissionCny(dec(accrued));
    row.setReleaseBasis("CONFIRMED");
    return row;
  }

  private static CommissionItemStateDO unverified(long id, long orderItemId) {
    var row = new CommissionItemStateDO();
    row.setId(id);
    row.setOrderId(10L);
    row.setOrderItemId(orderItemId);
    row.setKolId(null);
    row.setVerified(false);
    row.setAccruedCommissionCny(dec("0.00"));
    row.setReleasedCommissionCny(dec("0.00"));
    row.setReleaseBasis("UNVERIFIED");
    return row;
  }

  private static CommissionItemRevisionDO revision(long itemStateId, String rate) {
    var row = new CommissionItemRevisionDO();
    row.setItemStateId(itemStateId);
    row.setRate(dec(rate));
    return row;
  }

  private static AllocationOccupationRow alloc(
      long allocationId,
      long orderItemId,
      String occupied,
      long withdrawalId,
      String applicationNo,
      String status,
      Instant submittedAt,
      Instant paidAt) {
    return new AllocationOccupationRow(
        allocationId,
        orderItemId,
        dec(occupied),
        withdrawalId,
        applicationNo,
        status,
        submittedAt,
        paidAt);
  }

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }
}
