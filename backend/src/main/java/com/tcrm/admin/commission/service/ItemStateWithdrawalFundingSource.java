package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement side of the withdrawal contract (ADR-032 §6), read from task 62's commission item
 * state: released rows in FIFO order (release time, then order item id) feed the allocation, the
 * aggregate row feeds the withdrawal conditions. Occupations live in the withdrawal tables, so a
 * row's leftover is its released amount minus the funding statuses' occupations.
 */
@Service
public class ItemStateWithdrawalFundingSource implements WithdrawalFundingSource {
  private final CommissionItemStateMapper states;
  private final WithdrawalMapper withdrawals;
  private final CommissionRuleConfigService ruleConfig;

  public ItemStateWithdrawalFundingSource(
      CommissionItemStateMapper states,
      WithdrawalMapper withdrawals,
      CommissionRuleConfigService ruleConfig) {
    this.states = states;
    this.withdrawals = withdrawals;
    this.ruleConfig = ruleConfig;
  }

  @Override
  @Transactional(readOnly = true)
  public List<FundingRow> listReleasedRows(long kolId) {
    // Wall-clock now as a parameter: CURRENT_TIMESTAMP is the transaction start in PostgreSQL,
    // which would hide rows released inside the same transaction.
    List<CommissionItemStateDO> rows = states.listReleasedForWithdrawal(kolId, Instant.now());
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, BigDecimal> occupied = new HashMap<>();
    for (WithdrawalMapper.ItemOccupation occupation :
        withdrawals.sumOccupiedByItem(
            kolId, rows.stream().map(CommissionItemStateDO::getOrderItemId).toList())) {
      occupied.put(occupation.orderItemId(), occupation.occupiedCny());
    }
    List<FundingRow> funding = new ArrayList<>(rows.size());
    for (CommissionItemStateDO row : rows) {
      BigDecimal leftover =
          nz(row.getReleasedCommissionCny()).subtract(nz(occupied.get(row.getOrderItemId())));
      if (leftover.signum() > 0) {
        funding.add(new FundingRow(row.getOrderItemId(), row.getId(), leftover));
      }
    }
    return List.copyOf(funding);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal frozenCny(long kolId) {
    KolAccrual aggregate = aggregate(kolId);
    return aggregate.frozen();
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal estimatedCny(long kolId) {
    return aggregate(kolId).estimated();
  }

  @Override
  @Transactional(readOnly = true)
  public long unverifiedCount(long kolId) {
    for (var row : states.aggregateByKol(List.of(kolId))) {
      return row.unverifiedCount() == null ? 0L : row.unverifiedCount();
    }
    return 0L;
  }

  @Override
  @Transactional(readOnly = true)
  public int configVersion(long kolId) {
    // The version the conditions' minimum came from; 0 while only the defaults apply (ADR-032 §4).
    return ruleConfig.current().version();
  }

  private record KolAccrual(BigDecimal estimated, BigDecimal frozen) {}

  private KolAccrual aggregate(long kolId) {
    for (var row : states.aggregateByKol(List.of(kolId))) {
      BigDecimal estimated = nz(row.estimatedCny());
      BigDecimal frozen = row.frozenCny() == null ? BigDecimal.ZERO.setScale(2) : row.frozenCny();
      return new KolAccrual(estimated, frozen);
    }
    return new KolAccrual(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
