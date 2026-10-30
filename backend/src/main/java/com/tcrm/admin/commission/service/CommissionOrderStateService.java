package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.OccupationRow;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator-portal commission view of order lines (task 62, design §6.3). Implements the {@code
 * order} module's port so the dependency direction stays commission → order.api. Occupation-based
 * states ({@code WITHDRAWING}, {@code WITHDRAWN}) land with task 63's allocation table.
 */
@Service
public class CommissionOrderStateService implements CommissionOrderStateApi {
  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final WithdrawalMapper withdrawals;

  public CommissionOrderStateService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      WithdrawalMapper withdrawals) {
    this.states = states;
    this.revisions = revisions;
    this.withdrawals = withdrawals;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemCommissionStateDTO> listByOrderItemIds(
      long kolId, Collection<Long> orderItemIds) {
    if (orderItemIds == null || orderItemIds.isEmpty()) {
      return List.of();
    }
    return toDtos(kolId, states.listByOrderItemIds(orderItemIds));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemCommissionStateDTO> listByKolAndOrderIds(long kolId, Collection<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return toDtos(kolId, states.listByOrderIds(orderIds));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemCommissionStateDTO> listByKol(long kolId) {
    return toDtos(kolId, states.listByKol(kolId));
  }

  private List<ItemCommissionStateDTO> toDtos(long kolId, List<CommissionItemStateDO> rows) {
    List<CommissionItemStateDO> own =
        rows.stream().filter(row -> kolId == nz(row.getKolId())).toList();
    Map<Long, BigDecimal> rates = latestRates(own);
    Map<Long, String> occupationByItem = occupations(own);
    List<ItemCommissionStateDTO> result = new ArrayList<>(own.size());
    for (CommissionItemStateDO row : own) {
      result.add(toDto(row, rates.get(row.getId()), occupationByItem));
    }
    return result;
  }

  /**
   * Withdrawal occupations of the creator's item lines (task 63): which lines are occupied by an
   * active application ({@code WITHDRAWING}) and which by a paid one ({@code WITHDRAWN}).
   */
  private Map<Long, String> occupations(List<CommissionItemStateDO> rows) {
    Map<Long, String> result = new HashMap<>();
    if (rows.isEmpty()) {
      return result;
    }
    Set<Long> paidItems = new HashSet<>();
    Set<Long> activeItems = new HashSet<>();
    for (OccupationRow row : withdrawals.listOccupationsByKol(kolIdOf(rows))) {
      if ("PAID".equals(row.status())) {
        paidItems.add(row.orderItemId());
      } else {
        activeItems.add(row.orderItemId());
      }
    }
    for (Long item : paidItems) {
      result.put(item, STATUS_WITHDRAWN);
    }
    for (Long item : activeItems) {
      result.putIfAbsent(item, STATUS_WITHDRAWING);
    }
    return result;
  }

  private static long kolIdOf(List<CommissionItemStateDO> rows) {
    return rows.isEmpty() ? 0L : nz(rows.getFirst().getKolId());
  }

  private Map<Long, BigDecimal> latestRates(List<CommissionItemStateDO> rows) {
    Map<Long, BigDecimal> rates = new HashMap<>();
    if (rows.isEmpty()) {
      return rates;
    }
    for (CommissionItemRevisionDO revision :
        revisions.listLatestByItemStateIds(
            rows.stream().map(CommissionItemStateDO::getId).toList())) {
      rates.put(revision.getItemStateId(), revision.getRate());
    }
    return rates;
  }

  private ItemCommissionStateDTO toDto(
      CommissionItemStateDO row, BigDecimal rate, Map<Long, String> occupations) {
    BigDecimal commissionAmount = null;
    BigDecimal releasedAmount = null;
    String status = STATUS_INVALID;
    if (Boolean.TRUE.equals(row.getVerified())) {
      BigDecimal accrued =
          row.getAccruedCommissionCny() == null ? BigDecimal.ZERO : row.getAccruedCommissionCny();
      BigDecimal released =
          row.getReleasedCommissionCny() == null ? BigDecimal.ZERO : row.getReleasedCommissionCny();
      commissionAmount = accrued;
      releasedAmount = released;
      // The withdrawal occupation (task 63) outranks the release ladder: an occupied line is
      // WITHDRAWING until its application is paid, then WITHDRAWN.
      String occupation = occupations.get(row.getOrderItemId());
      if (occupation != null) {
        status = occupation;
      } else if (Boolean.TRUE.equals(row.getAfterSales())) {
        // An unconcluded refund keeps the line frozen even when it was released before (task 81
        // PR review, 2026-10-30): the line cannot be withdrawn while the refund is open.
        status = STATUS_FROZEN;
      } else if (accrued.compareTo(BigDecimal.ZERO) > 0
          && released.compareTo(BigDecimal.ZERO) > 0) {
        status = STATUS_WITHDRAWABLE;
      } else if (accrued.compareTo(BigDecimal.ZERO) > 0) {
        status = STATUS_FROZEN;
      }
    }
    return new ItemCommissionStateDTO(
        nz(row.getOrderId()),
        nz(row.getOrderItemId()),
        status,
        rate,
        commissionAmount,
        releasedAmount);
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }
}
