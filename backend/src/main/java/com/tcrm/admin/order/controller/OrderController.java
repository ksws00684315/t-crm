package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.service.OrderChainDTO;
import com.tcrm.admin.order.service.OrderDTO;
import com.tcrm.admin.order.service.OrderDetailDTO;
import com.tcrm.admin.order.service.OrderService;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import com.tcrm.admin.order.service.OrderSyncStateDTO;
import com.tcrm.admin.order.service.SyncFailureDTO;
import com.tcrm.admin.order.service.UnmatchedEvidenceDTO;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Orders and manual matching, scoped by company and payment-time BD snapshot. */
@RestController
@RequestMapping("/admin-api/order")
public class OrderController {
  private final OrderService orders;
  private final OrderSyncCoordinator syncs;

  public OrderController(OrderService orders, OrderSyncCoordinator syncs) {
    this.orders = orders;
    this.syncs = syncs;
  }

  /** order:view; paginated orders, lines and completed refund totals; no buyer data. */
  @GetMapping("/orders")
  @SaCheckPermission("order:view")
  public R<PageResult<OrderDTO>> list(@Valid @ModelAttribute OrderQuery query) {
    return R.ok(orders.page(query.filter(), false));
  }

  /** order:view; one order with amounts, provenance and product lines (task 40). */
  @GetMapping("/orders/{id}")
  @SaCheckPermission("order:view")
  public R<OrderDetailDTO> detail(@PathVariable @Positive Long id) {
    return R.ok(orders.detail(id));
  }

  /** order:view; per-line chain of copy job, link, order line and refunds (task 40). */
  @GetMapping("/orders/{id}/chain")
  @SaCheckPermission("order:view")
  public R<OrderChainDTO> chain(@PathVariable @Positive Long id) {
    return R.ok(orders.chain(id));
  }

  /**
   * order:manage; 202 once the background worker acquires the shop lock; 409 ORDER_SYNC_RUNNING.
   */
  @PostMapping("/syncs")
  @SaCheckPermission("order:manage")
  @AuditLog(module = "ORDER", action = "Synchronize shop orders")
  public ResponseEntity<R<Void>> sync(@Valid @RequestBody OrderSyncRequest request) {
    syncs.trigger(request.shopId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(null));
  }

  /** order:view; every shop of the company with sync status; shops without a record included. */
  @GetMapping("/sync-states")
  @SaCheckPermission("order:view")
  public R<List<OrderSyncStateDTO>> syncStates() {
    return R.ok(orders.listSyncStates());
  }

  /** order:view; recent order sync and import failures of the company (task 40). */
  @GetMapping("/sync-failures")
  @SaCheckPermission("order:view")
  public R<List<SyncFailureDTO>> syncFailures() {
    return R.ok(orders.listSyncFailures());
  }

  /** order:manage; delete company sync failures older than the given days (task 40). */
  @DeleteMapping("/sync-failures")
  @SaCheckPermission("order:manage")
  public R<Integer> deleteSyncFailures(@RequestParam @Min(1) @Max(3650) int olderThanDays) {
    return R.ok(orders.deleteSyncFailures(olderThanDays));
  }

  /** order:manage; paginated CONFLICT / UNMATCHED orders and lines. */
  @GetMapping("/unmatched")
  @SaCheckPermission("order:manage")
  public R<PageResult<OrderDTO>> unmatched(@Valid @ModelAttribute OrderQuery query) {
    return R.ok(orders.page(query.filter(), true));
  }

  /** order:manage; read-only match evidence of one order line of the manual pool (task 40). */
  @GetMapping("/unmatched/{orderItemId}/evidence")
  @SaCheckPermission("order:manage")
  public R<UnmatchedEvidenceDTO> evidence(@PathVariable @Positive Long orderItemId) {
    return R.ok(orders.evidence(orderItemId));
  }

  /** order:manage; 400 invalid target/reason, 404 foreign order or KOL, 403 outside OWN scope. */
  @PutMapping("/orders/{id}/attribution")
  @SaCheckPermission("order:manage")
  @AuditLog(module = "ORDER", action = "Assign order attribution")
  public R<Void> assign(
      @PathVariable @Positive Long id, @Valid @RequestBody OrderAttributionRequest request) {
    orders.assign(id, request.kolId(), Boolean.TRUE.equals(request.nonKol()), request.reason());
    return R.ok(null);
  }

  /** order:manage; explicit fresh automatic attribution following mapping import. */
  @PostMapping("/orders/{id}/reattribution")
  @SaCheckPermission("order:manage")
  @AuditLog(module = "ORDER", action = "Reattribute order")
  public R<Void> reattribute(@PathVariable @Positive Long id) {
    orders.reattribute(id);
    return R.ok(null);
  }
}
