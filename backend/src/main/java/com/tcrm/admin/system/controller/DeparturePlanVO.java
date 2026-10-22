package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.model.DepartureInitiator;
import com.tcrm.admin.system.model.DeparturePlanStatus;
import com.tcrm.admin.system.model.DeparturePlanType;
import com.tcrm.admin.system.model.DepartureResult;
import com.tcrm.admin.system.service.DepartureService;
import java.time.Instant;

/**
 * One departure plan as returned by every plan endpoint. {@code awaitingHeir} is the "heir still to
 * be named" hint of the account list: a pending plan without an heir.
 */
public record DeparturePlanVO(
    Long id,
    Long userId,
    DeparturePlanType type,
    Instant effectiveAt,
    Long heirUserId,
    String heirName,
    DepartureInitiator initiator,
    String reason,
    DeparturePlanStatus status,
    Instant executedAt,
    DepartureResult result,
    String resultMessage,
    boolean awaitingHeir) {
  public static DeparturePlanVO from(DepartureService.PlanDTO plan) {
    if (plan == null) {
      return null;
    }
    return new DeparturePlanVO(
        plan.id(),
        plan.userId(),
        plan.type(),
        plan.effectiveAt(),
        plan.heirUserId(),
        plan.heirName(),
        plan.initiator(),
        plan.reason(),
        plan.status(),
        plan.executedAt(),
        plan.result(),
        plan.resultMessage(),
        plan.status() == DeparturePlanStatus.SCHEDULED && plan.heirUserId() == null);
  }
}
