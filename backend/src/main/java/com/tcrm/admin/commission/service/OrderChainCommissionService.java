package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper.ItemLedgerRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.AllocationOccupationRow;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.order.api.OrderChainCommissionApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commission side of the admin order chain (task 72, design §6.2, handoff B07–B09). Assembles the
 * per-line commission blocks, the occupation views and the finance flows from the live snapshots,
 * the wallet ledger and the withdrawal allocations; permission gating stays with the order module.
 */
@Service
public class OrderChainCommissionService implements OrderChainCommissionApi {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final CommissionWalletMapper wallet;
  private final WithdrawalMapper withdrawals;

  public OrderChainCommissionService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      CommissionWalletMapper wallet,
      WithdrawalMapper withdrawals) {
    this.states = states;
    this.revisions = revisions;
    this.wallet = wallet;
    this.withdrawals = withdrawals;
  }

  @Override
  @Transactional(readOnly = true)
  public OrderCommissionChainDTO chainOfOrder(long orderId, Collection<Long> orderItemIds) {
    List<Long> itemIds = orderItemIds == null ? List.of() : List.copyOf(orderItemIds);
    if (itemIds.isEmpty()) {
      return new OrderCommissionChainDTO(ZERO, true, List.of(), List.of());
    }
    List<CommissionItemStateDO> rows = states.listByOrderIds(List.of(orderId));
    Map<Long, CommissionItemStateDO> stateByItem = new LinkedHashMap<>();
    BigDecimal net = ZERO;
    boolean allVerified = true;
    for (CommissionItemStateDO row : rows) {
      stateByItem.put(row.getOrderItemId(), row);
      net = net.add(nz(row.getAccruedCommissionCny()));
      allVerified = allVerified && Boolean.TRUE.equals(row.getVerified());
    }
    Map<Long, BigDecimal> rateByItem = latestRates(rows);
    List<AllocationOccupationRow> allocations = withdrawals.listAllocationsByOrderItems(itemIds);
    List<FlowDTO> flows = new ArrayList<>();
    flows.addAll(ledgerFlows(stateByItem));
    flows.addAll(allocationFlows(allocations));
    flows.sort(
        Comparator.comparing(FlowDTO::businessAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(FlowDTO::flowNo));
    List<LineCommissionDTO> lines =
        itemIds.stream().map(id -> lineOf(id, stateByItem, rateByItem, allocations)).toList();
    return new OrderCommissionChainDTO(
        net.setScale(2, RoundingMode.UNNECESSARY), allVerified, lines, flows);
  }

  /** Pay-time rate of the latest revision per item state; states without revisions stay absent. */
  private Map<Long, BigDecimal> latestRates(List<CommissionItemStateDO> rows) {
    if (rows.isEmpty()) {
      return Map.of();
    }
    List<Long> stateIds = rows.stream().map(CommissionItemStateDO::getId).toList();
    Map<Long, BigDecimal> rateByState = new HashMap<>();
    for (CommissionItemRevisionDO revision : revisions.listLatestByItemStateIds(stateIds)) {
      rateByState.put(revision.getItemStateId(), revision.getRate());
    }
    Map<Long, BigDecimal> rateByItem = new HashMap<>();
    for (CommissionItemStateDO row : rows) {
      rateByItem.put(row.getOrderItemId(), rateByState.get(row.getId()));
    }
    return rateByItem;
  }

  private LineCommissionDTO lineOf(
      long orderItemId,
      Map<Long, CommissionItemStateDO> stateByItem,
      Map<Long, BigDecimal> rateByItem,
      List<AllocationOccupationRow> allocations) {
    CommissionItemStateDO state = stateByItem.get(orderItemId);
    List<OrderChainCommissionApi.WithdrawalRefDTO> refs = new ArrayList<>();
    BigDecimal occupied = ZERO;
    for (AllocationOccupationRow allocation : allocations) {
      if (allocation.orderItemId() != orderItemId) {
        continue;
      }
      refs.add(
          new WithdrawalRefDTO(
              allocation.withdrawalId(),
              allocation.applicationNo(),
              allocation.occupiedCny(),
              allocation.status(),
              allocation.paidAt()));
      if (OCCUPYING_STATUSES.contains(allocation.status())) {
        occupied = occupied.add(nz(allocation.occupiedCny()));
      }
    }
    BigDecimal netCommission =
        state != null && Boolean.TRUE.equals(state.getVerified())
            ? nz(state.getAccruedCommissionCny())
            : null;
    return new LineCommissionDTO(
        orderItemId,
        rateByItem.get(orderItemId),
        netCommission,
        state == null ? null : state.getReleaseBasis(),
        occupied.setScale(2, RoundingMode.UNNECESSARY),
        List.copyOf(refs));
  }

  /**
   * Wallet entries of the order's item snapshots: release credits are increases, refund and
   * attribution clawbacks are decreases — the entry sign decides, never the flow type (B08).
   */
  private List<FlowDTO> ledgerFlows(Map<Long, CommissionItemStateDO> stateByItem) {
    if (stateByItem.isEmpty()) {
      return List.of();
    }
    List<ItemLedgerRow> entries =
        wallet.listByItemStates(
            stateByItem.values().stream().map(CommissionItemStateDO::getId).toList());
    Map<Long, Long> itemByState = new HashMap<>();
    for (CommissionItemStateDO state : stateByItem.values()) {
      itemByState.put(state.getId(), state.getOrderItemId());
    }
    List<FlowDTO> flows = new ArrayList<>();
    for (ItemLedgerRow entry : entries) {
      Long orderItemId = itemByState.get(entry.sourceId());
      if (orderItemId == null || entry.amountCny() == null || entry.amountCny().signum() == 0) {
        continue;
      }
      flows.add(
          new FlowDTO(
              orderItemId,
              "CL" + entry.id(),
              FLOW_KOL_COMMISSION,
              entry.amountCny().signum() > 0 ? DIRECTION_INCREASE : DIRECTION_DECREASE,
              entry.amountCny().abs().setScale(2, RoundingMode.UNNECESSARY),
              entry.occurredAt(),
              null,
              null));
    }
    return flows;
  }

  /**
   * Payout flows split per allocation, so partial and repeated withdrawals each carry their own
   * application number and payment time; only occupying applications flow money (B08/B09).
   */
  private List<FlowDTO> allocationFlows(List<AllocationOccupationRow> allocations) {
    List<FlowDTO> flows = new ArrayList<>();
    for (AllocationOccupationRow allocation : allocations) {
      if (!OCCUPYING_STATUSES.contains(allocation.status())) {
        continue;
      }
      flows.add(
          new FlowDTO(
              allocation.orderItemId(),
              "WA" + allocation.allocationId(),
              FLOW_KOL_COMMISSION,
              DIRECTION_DECREASE,
              nz(allocation.occupiedCny()).setScale(2, RoundingMode.UNNECESSARY),
              allocation.submittedAt(),
              allocation.paidAt(),
              allocation.applicationNo()));
    }
    return flows;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? ZERO : value;
  }
}
