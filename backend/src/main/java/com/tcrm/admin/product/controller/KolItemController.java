package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.service.KolItemService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator-item page (task 39). OWN-scope staff see mappings of their assigned KOLs. */
@Validated
@RestController
@RequestMapping("/admin-api/product/kol-items")
public class KolItemController {
  private final KolItemService items;

  public KolItemController(KolItemService items) {
    this.items = items;
  }

  @GetMapping
  @SaCheckPermission("product:view")
  public R<PageResult<KolItemVO>> list(@Valid @ModelAttribute KolItemQuery query) {
    return R.ok(items.page(query.toFilter()).map(KolItemVO::from));
  }

  @GetMapping("/{id}")
  @SaCheckPermission("product:view")
  public R<KolItemDetailVO> detail(@PathVariable @Min(1) long id) {
    return R.ok(KolItemDetailVO.from(items.detail(id)));
  }
}
