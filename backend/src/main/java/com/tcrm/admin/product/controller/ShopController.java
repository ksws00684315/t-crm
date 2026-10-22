package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kol.api.CopyTargetShopApi.CopyChannelDTO;
import com.tcrm.admin.product.service.ShopService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
  public R<PageResult<ShopVO>> list(
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String shopName,
      @RequestParam(required = false) String channel,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    if (page < 1 || size < 1 || size > 100) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var result = shops.page(shopCode, shopName, channel, page, size);
    return R.ok(
        new PageResult<>(
            result.items().stream().map(ShopVO::from).toList(), result.total(), page, size));
  }

  @GetMapping("/options")
  @SaCheckPermission("product:view")
  public R<List<ShopOptionVO>> options() {
    return R.ok(shops.listOptions().stream().map(ShopOptionVO::from).toList());
  }

  @PostMapping
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Create shop")
  public R<ShopVO> create(@Valid @RequestBody CreateShopRequest r) {
    return R.ok(ShopVO.from(shops.create(r.toCommand())));
  }

  @GetMapping("/copy-channels")
  @SaCheckPermission("kol:copy-token")
  public R<List<CopyChannelDTO>> copyChannels() {
    return R.ok(shops.copyChannels());
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

  @PutMapping("/{id}/source")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Mark source shop")
  public R<Void> markSource(@PathVariable @Min(1) Long id) {
    shops.markSource(id, true);
    return R.ok(null);
  }

  @DeleteMapping("/{id}/source")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Unmark source shop")
  public R<Void> clearSource(@PathVariable @Min(1) Long id) {
    shops.markSource(id, false);
    return R.ok(null);
  }

  @PutMapping("/{id}/copy-pool")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Join shop copy pool")
  public R<Void> joinCopyPool(@PathVariable @Min(1) Long id) {
    shops.joinCopyPool(id);
    return R.ok(null);
  }

  @DeleteMapping("/{id}/copy-pool")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Exit shop copy pool")
  public R<Void> exitCopyPool(@PathVariable @Min(1) Long id) {
    shops.exitCopyPool(id);
    return R.ok(null);
  }

  @PostMapping("/{id}/capacity-refreshes")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Refresh shop capacity")
  public R<ShopVO> refreshCapacity(@PathVariable @Min(1) Long id) {
    return R.ok(ShopVO.from(shops.refreshCapacity(id)));
  }

  @PostMapping("/{id}/api-checks")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Check shop APIs")
  public R<ShopVO> checkApis(@PathVariable @Min(1) Long id) {
    return R.ok(ShopVO.from(shops.checkApis(id)));
  }
}
