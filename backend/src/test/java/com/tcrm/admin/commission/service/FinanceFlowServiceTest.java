package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.FinanceFlowMapper;
import com.tcrm.admin.commission.model.FinanceFlowDO;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Income-side subject postings of the finance flow ledger (task 158 §2.1/§2.2): pre-income, the
 * payment transfer, refund outflow plus income deduction, the settlement transfer of the frozen
 * balance, and the close postings. Idempotent per business fact.
 */
class FinanceFlowServiceTest {
  private static final Instant PLACED = Instant.parse("2026-11-22T02:00:00Z");
  private static final Instant PAID = Instant.parse("2026-11-22T03:00:00Z");
  private static final Instant REFUNDED = Instant.parse("2026-11-22T04:00:00Z");
  private static final Instant SETTLED = Instant.parse("2026-11-22T05:00:00Z");
  private static final Instant UPDATED = Instant.parse("2026-11-22T06:00:00Z");
  private static final long TENANT = 7L;
  private static final long ORDER = 9001L;
  private static final long ITEM = 9101L;

  private final FinanceFlowMapper flows = mock(FinanceFlowMapper.class);
  private final Map<String, FinanceFlowDO> store = new HashMap<>();
  private FinanceFlowService service;

  @BeforeEach
  void prepare() {
    store.clear();
    when(flows.findBySourceKey(anyLong(), anyString()))
        .thenAnswer(invocation -> store.get(invocation.getArgument(1)));
    when(flows.insert(any(FinanceFlowDO.class)))
        .thenAnswer(
            invocation -> {
              FinanceFlowDO row = invocation.getArgument(0);
              if (store.putIfAbsent(row.getSourceKey(), row) != null) {
                throw new DuplicateKeyException(row.getSourceKey());
              }
              return 1;
            });
    when(flows.selectByOrder(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              long orderId = invocation.getArgument(1);
              List<FinanceFlowDO> rows = new ArrayList<>();
              for (FinanceFlowDO row : store.values()) {
                if (orderId == (row.getOrderId() == null ? -1L : row.getOrderId())) {
                  rows.add(row);
                }
              }
              rows.sort(
                  Comparator.comparing(FinanceFlowDO::getOccurredAt)
                      .thenComparing(FinanceFlowDO::getId));
              return rows;
            });
    when(flows.selectClawbackProvisional(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              long orderItemId = invocation.getArgument(1);
              List<FinanceFlowDO> rows = new ArrayList<>();
              for (FinanceFlowDO row : store.values()) {
                if (FinanceFlowService.COMMISSION_CLAWBACK.equals(row.getSubject())
                    && Long.valueOf(orderItemId).equals(row.getOrderItemId())
                    && (FinanceFlowService.EVIDENCE_PROVISIONAL.equals(row.getEvidence())
                        || FinanceFlowService.EVIDENCE_RESTORE.equals(row.getEvidence()))) {
                  rows.add(row);
                }
              }
              return rows;
            });
    service = new FinanceFlowService(flows, () -> TENANT);
  }

  @Test
  void shouldBookPreIncomeAtUnpaidRegistrationAndStayIdempotent() {
    service.syncOrderFinance(unpaidEvent("UNPAID", "30.00"));
    service.syncOrderFinance(unpaidEvent("UNPAID", "30.00"));
    assertThat(entries(FinanceFlowService.PRE_INCOME))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getAmountCny()).isEqualByComparingTo("30.00");
              assertThat(row.getOccurredAt()).isEqualTo(PLACED);
              assertThat(row.getOrderId()).isEqualTo(ORDER);
            });
  }

  @Test
  void shouldTransferPreIncomeAndBookFrozenIncomeAtPayment() {
    service.syncOrderFinance(unpaidEvent("UNPAID", "30.00"));
    service.syncOrderFinance(paidEvent(null, null, "28.50", null));
    service.syncOrderFinance(paidEvent(null, null, "28.50", null));
    assertThat(sum(FinanceFlowService.PRE_INCOME)).isEqualByComparingTo("0");
    assertThat(entries(FinanceFlowService.FROZEN_INCOME))
        .singleElement()
        .satisfies(row -> assertThat(row.getAmountCny()).isEqualByComparingTo("28.50"));
  }

  @Test
  void shouldBookRefundOutflowAndDeductTheIncomeSubjectInEffect() {
    // Refund before settlement reduces frozen income.
    service.syncOrderFinance(
        paidEvent(null, List.of(refund(1L, ITEM, "5.00", REFUNDED)), "28.50", null));
    assertThat(entries(FinanceFlowService.OUTFLOW))
        .singleElement()
        .satisfies(row -> assertThat(row.getAmountCny()).isEqualByComparingTo("5.00"));
    assertThat(sum(FinanceFlowService.FROZEN_INCOME)).isEqualByComparingTo("23.50");
    // Refund after settlement reduces withdrawable income instead.
    service.syncOrderFinance(
        paidEvent(
            SETTLED,
            List.of(
                refund(1L, ITEM, "5.00", REFUNDED),
                refund(2L, ITEM, "2.00", SETTLED.plusSeconds(60))),
            "28.50",
            "28.50"));
    assertThat(sum(FinanceFlowService.WITHDRAWABLE_INCOME)).isEqualByComparingTo("21.50");
    assertThat(entries(FinanceFlowService.OUTFLOW)).hasSize(2);
  }

  @Test
  void shouldTransferTheFrozenBalanceToWithdrawableAtSettlement() {
    service.syncOrderFinance(
        paidEvent(null, List.of(refund(1L, ITEM, "5.00", REFUNDED)), "28.50", "28.50"));
    service.syncOrderFinance(
        paidEvent(SETTLED, List.of(refund(1L, ITEM, "5.00", REFUNDED)), "28.50", "28.50"));
    assertThat(sum(FinanceFlowService.FROZEN_INCOME)).isEqualByComparingTo("0");
    assertThat(sum(FinanceFlowService.WITHDRAWABLE_INCOME)).isEqualByComparingTo("23.50");
    // The transfer books the balance (pay less pre-settle refunds), the goods income figure is
    // evidence only.
    assertThat(entries(FinanceFlowService.WITHDRAWABLE_INCOME))
        .singleElement()
        .satisfies(row -> assertThat(row.getEvidence()).contains("货款收入＝28.50"));
  }

  @Test
  void shouldCancelPreIncomeOnUnpaidClose() {
    service.syncOrderFinance(unpaidEvent("UNPAID", "30.00"));
    service.syncOrderFinance(unpaidEvent("CLOSED", "30.00"));
    assertThat(sum(FinanceFlowService.PRE_INCOME)).isEqualByComparingTo("0");
    assertThat(entries(FinanceFlowService.PRE_INCOME)).hasSize(2);
  }

  @Test
  void shouldBookPaidCloseAsOutflowAndClearIncomeSubjects() {
    service.syncOrderFinance(
        paidEvent(SETTLED, List.of(refund(1L, ITEM, "5.00", REFUNDED)), "28.50", "28.50"));
    service.syncOrderFinance(
        paidClosedEvent(SETTLED, List.of(refund(1L, ITEM, "5.00", REFUNDED)), "28.50", "28.50"));
    assertThat(sum(FinanceFlowService.FROZEN_INCOME)).isEqualByComparingTo("0");
    assertThat(sum(FinanceFlowService.WITHDRAWABLE_INCOME)).isEqualByComparingTo("0");
    assertThat(sum(FinanceFlowService.OUTFLOW)).isEqualByComparingTo("28.50");
  }

  @Test
  void shouldZeroTheIncomeSubjectsOnAFullRefund() {
    service.syncOrderFinance(
        paidEvent(null, List.of(refund(1L, ITEM, "28.50", REFUNDED)), "28.50", null));
    assertThat(sum(FinanceFlowService.FROZEN_INCOME)).isEqualByComparingTo("0");
    assertThat(sum(FinanceFlowService.OUTFLOW)).isEqualByComparingTo("28.50");
  }

  @Test
  void shouldFallBackToWithdrawableForARefundDiscoveredAfterTheSettlementTransfer() {
    // The refund concluded before settlement but is first seen after the transfer already moved
    // the frozen balance out: the deduction lands on withdrawable income (task 159 review).
    service.syncOrderFinance(paidEvent(SETTLED, List.of(), "28.50", "28.50"));
    service.syncOrderFinance(
        paidEvent(
            SETTLED,
            List.of(refund(1L, ITEM, "5.00", SETTLED.minusSeconds(3600))),
            "28.50",
            "28.50"));
    assertThat(sum(FinanceFlowService.FROZEN_INCOME)).isEqualByComparingTo("0");
    assertThat(sum(FinanceFlowService.WITHDRAWABLE_INCOME)).isEqualByComparingTo("23.50");
    assertThat(sum(FinanceFlowService.OUTFLOW)).isEqualByComparingTo("5.00");
  }

  @Test
  void recordShouldSkipZeroAmountsAndDuplicateSourceKeys() {
    FinanceFlowService.Command zero =
        new FinanceFlowService.Command(
            TENANT,
            FinanceFlowService.OUTFLOW,
            BigDecimal.ZERO,
            PAID,
            ORDER,
            null,
            null,
            FinanceFlowService.SOURCE_ORDER,
            ORDER,
            "FIN:zero",
            null);
    assertThat(service.record(zero)).isFalse();
    FinanceFlowService.Command once =
        new FinanceFlowService.Command(
            TENANT,
            FinanceFlowService.OUTFLOW,
            new BigDecimal("1.00"),
            PAID,
            ORDER,
            null,
            null,
            FinanceFlowService.SOURCE_ORDER,
            ORDER,
            "FIN:dup",
            null);
    assertThat(service.record(once)).isTrue();
    assertThat(service.record(once)).isFalse();
    assertThat(store).hasSize(1);
    assertThat(store.values().iterator().next().getFlowNo()).startsWith("FF");
  }

  @Test
  void provisionalClawbackNetShouldSumHoldsMinusRestorations() {
    service.record(
        new FinanceFlowService.Command(
            TENANT,
            FinanceFlowService.COMMISSION_CLAWBACK,
            new BigDecimal("4.00"),
            PAID,
            ORDER,
            ITEM,
            101L,
            FinanceFlowService.SOURCE_REVISION,
            1L,
            "FIN:hold",
            FinanceFlowService.EVIDENCE_PROVISIONAL));
    assertThat(service.provisionalClawbackNet(TENANT, ITEM)).isEqualByComparingTo("4.00");
    service.record(
        new FinanceFlowService.Command(
            TENANT,
            FinanceFlowService.COMMISSION_CLAWBACK,
            new BigDecimal("-3.00"),
            SETTLED,
            ORDER,
            ITEM,
            101L,
            FinanceFlowService.SOURCE_REVISION,
            1L,
            "FIN:restore",
            FinanceFlowService.EVIDENCE_RESTORE));
    assertThat(service.provisionalClawbackNet(TENANT, ITEM)).isEqualByComparingTo("1.00");
  }

  private List<FinanceFlowDO> entries(String subject) {
    List<FinanceFlowDO> rows = new ArrayList<>();
    for (FinanceFlowDO row : store.values()) {
      if (subject.equals(row.getSubject())) {
        rows.add(row);
      }
    }
    rows.sort(Comparator.comparing(FinanceFlowDO::getId));
    return rows;
  }

  private BigDecimal sum(String subject) {
    return entries(subject).stream()
        .map(FinanceFlowDO::getAmountCny)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static OrderItemsCommissionEvent.RefundFacts refund(
      long id, long itemId, String amount, Instant at) {
    return new OrderItemsCommissionEvent.RefundFacts(
        id, itemId, new BigDecimal(amount), "SUCCESS", at);
  }

  private static OrderItemsCommissionEvent unpaidEvent(String status, String orderTotal) {
    return new OrderItemsCommissionEvent(
        ORDER,
        3L,
        null,
        new OrderItemsCommissionEvent.OrderFacts(
            status, null, new BigDecimal(orderTotal), PLACED, UPDATED, null, null, List.of()),
        List.of());
  }

  private static OrderItemsCommissionEvent paidEvent(
      Instant settledTime,
      List<OrderItemsCommissionEvent.RefundFacts> refunds,
      String payAmount,
      String settlementIncome) {
    return new OrderItemsCommissionEvent(
        ORDER,
        3L,
        PAID,
        new OrderItemsCommissionEvent.OrderFacts(
            "PAID",
            new BigDecimal(payAmount),
            null,
            PLACED,
            UPDATED,
            settledTime,
            settlementIncome == null ? null : new BigDecimal(settlementIncome),
            refunds == null ? List.of() : refunds),
        List.of());
  }

  private static OrderItemsCommissionEvent paidClosedEvent(
      Instant settledTime,
      List<OrderItemsCommissionEvent.RefundFacts> refunds,
      String payAmount,
      String settlementIncome) {
    return new OrderItemsCommissionEvent(
        ORDER,
        3L,
        PAID,
        new OrderItemsCommissionEvent.OrderFacts(
            "CLOSED",
            new BigDecimal(payAmount),
            null,
            PLACED,
            UPDATED,
            settledTime,
            settlementIncome == null ? null : new BigDecimal(settlementIncome),
            refunds == null ? List.of() : refunds),
        List.of());
  }
}
