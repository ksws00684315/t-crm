package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.DepartureService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Departure and disable plans of BD accounts (task 41). Super administrators schedule, edit and
 * cancel them, read the pending plan of one account and the list of eligible heirs. Errors:
 * validation 400, missing account 404, an existing or already ended plan 409, a rejected heir or a
 * non-BD target 422.
 */
@RestController
@RequestMapping("/admin-api/system/users")
@SaCheckRole("SUPER_ADMIN")
public class DeparturePlanController {
  private final DepartureService departures;

  public DeparturePlanController(DepartureService departures) {
    this.departures = departures;
  }

  /** BDs that may inherit creators, excluding the departing account itself. */
  @GetMapping("/departure-heirs")
  public R<List<DepartureHeirVO>> heirs(@RequestParam(required = false) Long excludeUserId) {
    return R.ok(departures.heirs(excludeUserId).stream().map(DepartureHeirVO::from).toList());
  }

  /** The pending plan of one account; {@code data} is null when it has none. */
  @GetMapping("/{id}/departure-plan")
  public R<DeparturePlanVO> plan(@PathVariable @Min(1) Long id) {
    return R.ok(DeparturePlanVO.from(departures.plan(id)));
  }

  /** Schedules the plan; a missing or past effective time runs it before the response returns. */
  @PostMapping("/{id}/departure-plan")
  @AuditLog(module = "User", action = "Schedule departure")
  public R<DeparturePlanVO> create(
      @PathVariable @Min(1) Long id, @Valid @RequestBody DeparturePlanRequest r) {
    return R.ok(
        DeparturePlanVO.from(
            departures.create(id, r.type(), r.effectiveAt(), r.heirUserId(), r.reason())));
  }

  /** Changes the heir, the effective time or the reason of a pending plan. */
  @PutMapping("/{id}/departure-plan")
  @AuditLog(module = "User", action = "Edit departure plan")
  public R<DeparturePlanVO> update(
      @PathVariable @Min(1) Long id, @Valid @RequestBody DeparturePlanRequest r) {
    return R.ok(
        DeparturePlanVO.from(departures.update(id, r.effectiveAt(), r.heirUserId(), r.reason())));
  }

  /** Withdraws the pending plan; the account keeps working and its creators stay put. */
  @DeleteMapping("/{id}/departure-plan")
  @AuditLog(module = "User", action = "Cancel departure plan")
  public R<Void> cancel(@PathVariable @Min(1) Long id) {
    departures.cancel(id);
    return R.ok(null);
  }
}
