package com.tcrm.admin.commission.api;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;

/** Scoped statement of current order commission and confirmed creator fund movements. */
public interface CreatorFundFlowApi {
  PageResult<FundFlow> list(AccountDTO user, Query query);

  PageResult<Application> applications(AccountDTO user, Query query);

  enum ApplicationStatus {
    PENDING_REVIEW,
    PENDING_PAYMENT,
    PAID
  }

  record Application(
      String id,
      FundType fundType,
      BigDecimal amount,
      ApplicationStatus status,
      String applicationNo,
      Instant appliedAt) {}

  Totals totals(AccountDTO user, Query query);

  record Totals(BigDecimal frozen, BigDecimal available, BigDecimal withdrawn, BigDecimal prepaid) {
    public BigDecimal commission() {
      return frozen.add(available).add(withdrawn);
    }
  }

  record Query(long creatorId, Long shopId, Instant from, Instant until, int page, int size) {}

  enum FundType {
    COMMISSION,
    PREPAY
  }

  enum Status {
    FROZEN,
    AVAILABLE,
    WITHDRAWN,
    CLAWBACK,
    PREPAID
  }

  enum Direction {
    INCREASE,
    DECREASE
  }

  record FundFlow(
      String id,
      FundType fundType,
      Direction direction,
      String orderNo,
      BigDecimal amount,
      Status status,
      String settlementNo,
      Instant occurredAt) {}
}
