package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.DepartureService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A BD resigning about itself (task 41), next to the other self-service account operations under
 * {@code /admin-api/auth}. Only staff accounts reach the handler; a staff account that is not a BD
 * gets 422 DEPARTURE_NOT_BD_ACCOUNT, a second pending plan 409, a past effective time 422.
 */
@RestController
@RequestMapping("/admin-api/auth/resignation")
@SaCheckRole("STAFF")
public class ResignationController {
  private final DepartureService departures;

  public ResignationController(DepartureService departures) {
    this.departures = departures;
  }

  /** The caller's own pending plan; {@code data} is null when there is none. */
  @GetMapping
  public R<DeparturePlanVO> own() {
    return R.ok(DeparturePlanVO.from(departures.ownPlan()));
  }

  @PostMapping
  @AuditLog(module = "Auth", action = "Submit resignation")
  public R<DeparturePlanVO> submit(@Valid @RequestBody ResignationRequest r) {
    return R.ok(DeparturePlanVO.from(departures.submitOwn(r.plannedLeaveAt(), r.reason())));
  }

  @DeleteMapping
  @AuditLog(module = "Auth", action = "Cancel resignation")
  public R<Void> cancel() {
    departures.cancelOwn();
    return R.ok(null);
  }
}
