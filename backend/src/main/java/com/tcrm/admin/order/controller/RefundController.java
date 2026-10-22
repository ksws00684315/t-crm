package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.order.service.RefundDTO;
import com.tcrm.admin.order.service.RefundDetailDTO;
import com.tcrm.admin.order.service.RefundFilterDTO;
import com.tcrm.admin.order.service.RefundQueryService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Refund queries (task 40 §3.3); BD accounts see only their own KOLs' refunds. */
@RestController
@RequestMapping("/admin-api/order")
public class RefundController {
  private final RefundQueryService refunds;

  public RefundController(RefundQueryService refunds) {
    this.refunds = refunds;
  }

  /** order:view; paginated refunds with order, shop and KOL display fields. */
  @GetMapping("/refunds")
  @SaCheckPermission("order:view")
  public R<PageResult<RefundDTO>> list(@Valid @ModelAttribute RefundQuery query) {
    return R.ok(refunds.page(toFilter(query)));
  }

  /** order:view; one refund with its linked order line. */
  @GetMapping("/refunds/{id}")
  @SaCheckPermission("order:view")
  public R<RefundDetailDTO> detail(@PathVariable @Positive Long id) {
    return R.ok(refunds.detail(id));
  }

  private static RefundFilterDTO toFilter(RefundQuery query) {
    return new RefundFilterDTO(
        query.refundNo(),
        query.orderNo(),
        query.channel(),
        query.refundStatus(),
        query.refundType(),
        query.page(),
        query.size());
  }
}
