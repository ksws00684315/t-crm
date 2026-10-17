package com.tcrm.admin.order.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.order.service.KolOrderDTO;
import com.tcrm.admin.order.service.KolOrderFilterDTO;
import com.tcrm.admin.order.service.KolOrderService;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only current-KOL orders; only the authenticated KOL's attributed records are exposed. */
@RestController
public class KolOrderController {
  private final KolOrderService orders;

  public KolOrderController(KolOrderService orders) {
    this.orders = orders;
  }

  /** Requires a KOL token; payFrom is inclusive, payTo exclusive, and size is capped at 50. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/orders")
  public R<PageResult<KolOrderDTO>> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant payFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant payTo,
      @RequestParam(required = false)
          @Pattern(regexp = "UNPAID|PAID|SHIPPED|RECEIVED|COMPLETED|CLOSED|UNKNOWN")
          String orderStatus,
      @RequestParam(defaultValue = "1") @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) Integer size) {
    return R.ok(orders.page(new KolOrderFilterDTO(payFrom, payTo, orderStatus, page, size)));
  }
}
