package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.product.service.ShopService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
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
import org.springframework.web.bind.annotation.RestController;

/** Shop management (task 05 §4.1, §5). Writes are SUPER_ADMIN only. */
@RestController
@RequestMapping("/admin-api/product/shops")
public class ShopController {
  private final ShopService shops;

  public ShopController(ShopService shops) {
    this.shops = shops;
  }

  @GetMapping
  @SaCheckPermission("product:view")
  public R<List<ShopVO>> list() {
    return R.ok(shops.list().stream().map(ShopVO::from).toList());
  }

  @PostMapping
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Create shop")
  public R<ShopVO> create(@Valid @RequestBody CreateShopRequest r) {
    return R.ok(ShopVO.from(shops.create(r.toCommand())));
  }

  @PutMapping("/{id}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Edit shop")
  public R<Void> edit(@PathVariable @Min(1) Long id, @Valid @RequestBody EditShopRequest r) {
    shops.update(id, r.toCommand());
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Change shop status")
  public R<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody ShopStatusRequest r) {
    shops.updateStatus(id, r.status());
    return R.ok(null);
  }

  @PutMapping("/{id}/copy-target")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Set default copy shop")
  public R<Void> setCopyTarget(@PathVariable @Min(1) Long id) {
    shops.setCopyTarget(id);
    return R.ok(null);
  }

  @DeleteMapping("/{id}/copy-target")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Clear default copy shop")
  public R<Void> clearCopyTarget(@PathVariable @Min(1) Long id) {
    shops.clearCopyTarget(id);
    return R.ok(null);
  }
}
