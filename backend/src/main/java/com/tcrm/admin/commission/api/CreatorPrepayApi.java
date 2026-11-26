package com.tcrm.admin.commission.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Confirmed prepay payments within the payment-time window. */
public interface CreatorPrepayApi {
  Map<Long, BigDecimal> paidByCreator(Collection<Long> creatorIds, Instant from, Instant until);

  /** Uses the applicant stored on the request, including after creator reassignment. */
  Map<Long, BigDecimal> paidByApplicant(
      Collection<Long> applicantIds, Collection<Long> creatorIds, Instant from, Instant until);
}
