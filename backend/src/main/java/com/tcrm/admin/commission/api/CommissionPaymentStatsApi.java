package com.tcrm.admin.commission.api;

import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CommissionPaymentStatsApi {
  private final WithdrawalMapper withdrawals;

  public CommissionPaymentStatsApi(WithdrawalMapper withdrawals) {
    this.withdrawals = withdrawals;
  }

  public Map<Long, BigDecimal> paidByCreator(List<Long> creatorIds, Instant from, Instant until) {
    if (creatorIds.isEmpty()) {
      return Map.of();
    }
    return withdrawals.sumPaidInWindow(creatorIds, from, until).stream()
        .collect(
            Collectors.toMap(
                WithdrawalMapper.PaidWindowRow::kolId, WithdrawalMapper.PaidWindowRow::amountCny));
  }
}
