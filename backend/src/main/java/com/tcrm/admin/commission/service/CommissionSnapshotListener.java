package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.api.CommissionItemApi.ItemSourceDTO;
import com.tcrm.admin.commission.api.CommissionItemApi.OrderSourceDTO;
import com.tcrm.admin.order.api.OrderItemsCommissionEvent;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registers commission source snapshots from order changes (task 62, design §4.8.1) and the
 * income-side finance flow postings (task 159, 财务流转口径规则 task 158). The listener runs before the
 * order transaction commits, so the snapshot, its wallet postings and the flow entries join the
 * order write atomically; a failure rolls the order write back too.
 */
@Component
public class CommissionSnapshotListener {
  private final com.tcrm.admin.commission.api.CommissionItemApi snapshots;
  private final FinanceFlowService financeFlows;

  public CommissionSnapshotListener(
      com.tcrm.admin.commission.api.CommissionItemApi snapshots, FinanceFlowService financeFlows) {
    this.snapshots = snapshots;
    this.financeFlows = financeFlows;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onOrderItemsChanged(OrderItemsCommissionEvent event) {
    List<ItemSourceDTO> items =
        event.items().stream().map(CommissionSnapshotListener::toSource).toList();
    snapshots.syncOrderItems(
        new OrderSourceDTO(event.orderId(), event.shopId(), event.payTime()), items);
    financeFlows.syncOrderFinance(event);
  }

  private static ItemSourceDTO toSource(OrderItemsCommissionEvent.ItemFacts facts) {
    return new ItemSourceDTO(
        facts.orderItemId(),
        facts.subOrderId(),
        facts.kolId(),
        facts.attributionType(),
        facts.productId(),
        facts.basisCny(),
        facts.basisIncludesRefund(),
        facts.successfulRefundCny(),
        facts.settlementState(),
        facts.settlementTime(),
        facts.completedTime(),
        facts.afterSales(),
        facts.basisCheckReason());
  }
}
