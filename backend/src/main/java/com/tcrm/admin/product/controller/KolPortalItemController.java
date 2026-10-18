package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tcrm.admin.product.service.KolPortalItemService;
import com.tcrm.admin.product.service.KolPortalItemService.KolPortalItemDTO;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator portal item list (task 26 §3.4): read-only, the token's own mappings. Identity and
 * company come from the KOL login state; no query parameter can widen them.
 */
@RestController
public class KolPortalItemController {
  private final KolPortalItemService items;

  public KolPortalItemController(KolPortalItemService items) {
    this.items = items;
  }

  /** Requires a KOL token; size is capped at 50 like the other portal lists. */
  @SaCheckLogin(type = StpKolUtil.TYPE)
  @GetMapping("/kol-api/items")
  public R<PageResult<KolPortalItemDTO>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) Integer size) {
    return R.ok(items.page(page, size, keyword));
  }
}
