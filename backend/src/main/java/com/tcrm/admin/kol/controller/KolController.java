package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kol.service.KolClaimService;
import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KOL management (task 04 / 25a / 29). Permission codes are checked here. A staff account with OWN
 * scope sees only KOLs assigned to them; changing the BD is super-admin only.
 */
@RestController
@RequestMapping("/admin-api/kol/kols")
public class KolController {
  private final KolService kols;
  private final KolPromotionService promotions;
  private final KolClaimService claims;

  public KolController(KolService kols, KolPromotionService promotions, KolClaimService claims) {
    this.kols = kols;
    this.promotions = promotions;
    this.claims = claims;
  }

  @GetMapping
  @SaCheckPermission("kol:view")
  public R<PageResult<KolVO>> list(@Valid @ModelAttribute KolQuery q) {
    return R.ok(kols.page(q.toFilter()).map(KolVO::from));
  }

  @GetMapping("/{id}")
  @SaCheckPermission("kol:view")
  public R<KolDetailVO> get(@PathVariable @Min(1) Long id) {
    return R.ok(KolDetailVO.from(kols.get(id)));
  }

  @PostMapping
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Create")
  public R<KolVO> create(@Valid @RequestBody CreateKolRequest r) {
    return R.ok(KolVO.from(kols.create(r.toCommand())));
  }

  @PutMapping("/{id}")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Edit")
  public R<Void> edit(@PathVariable @Min(1) Long id, @Valid @RequestBody EditKolRequest r) {
    kols.update(id, r.toCommand());
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Change status")
  public R<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody KolStatusRequest r) {
    kols.updateStatus(id, r.status());
    return R.ok(null);
  }

  /** Super administrator only. The service still rejects a staff account with OWN scope. */
  @PutMapping("/{id}/bd-user")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "KOL", action = "Change BD")
  public R<Void> bdUser(@PathVariable @Min(1) Long id, @Valid @RequestBody ChangeBdUserRequest r) {
    kols.updateBdUser(id, r.bdUserId());
    return R.ok(null);
  }

  /** Same permission as editing a KOL (task 12a §4.3); 409 when already activated. */
  @PostMapping("/{id}/invite")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Resend invite")
  public R<Void> invite(@PathVariable @Min(1) Long id) {
    kols.resendInvite(id);
    return R.ok(null);
  }

  @PostMapping("/{id}/review")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Review KOL")
  public R<Void> review(@PathVariable @Min(1) Long id, @Valid @RequestBody ReviewKolRequest r) {
    promotions.review(id, r.toCommand());
    return R.ok(null);
  }

  @PutMapping("/{id}/relation-status")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Change relation status")
  public R<Void> relationStatus(
      @PathVariable @Min(1) Long id, @Valid @RequestBody RelationStatusRequest r) {
    promotions.updateRelationStatus(id, r.status());
    return R.ok(null);
  }

  @GetMapping("/{id}/promotion-config")
  @SaCheckPermission("kol:view")
  public R<PromotionConfigVO> promotionConfig(@PathVariable @Min(1) Long id) {
    return R.ok(PromotionConfigVO.from(promotions.getConfig(id)));
  }

  @PutMapping("/{id}/promotion-config")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Save promotion config")
  public R<Void> savePromotionConfig(
      @PathVariable @Min(1) Long id, @Valid @RequestBody PromotionConfigRequest r) {
    promotions.updateConfig(id, r.toCommand());
    return R.ok(null);
  }

  @GetMapping("/{id}/promotion-records")
  @SaCheckPermission("kol:view")
  public R<PageResult<PromotionRecordVO>> promotionRecords(
      @PathVariable @Min(1) Long id,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return R.ok(promotions.pageRecords(id, page, size).map(PromotionRecordVO::from));
  }

  @PostMapping("/{id}/claim")
  @SaCheckPermission("kol:edit")
  @AuditLog(module = "KOL", action = "Claim KOL")
  public R<Void> claim(@PathVariable @Min(1) Long id) {
    claims.claim(id);
    return R.ok(null);
  }
}
