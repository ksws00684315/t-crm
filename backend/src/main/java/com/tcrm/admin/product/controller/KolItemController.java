package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.product.service.KolItemService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kol item mapping query (task 05 §5). OPERATOR+OWN is denied by the service; FINANCE may read.
 */
@RestController
@RequestMapping("/admin-api/product/kol-items")
public class KolItemController {
  private final KolItemService items;

  public KolItemController(KolItemService items) {
    this.items = items;
  }

  @GetMapping
  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
      mode = SaMode.OR)
  public R<PageResult<KolItemVO>> list(@Valid @ModelAttribute KolItemQuery q) {
    return R.ok(items.page(q.toFilter()).map(KolItemVO::from));
  }
}
