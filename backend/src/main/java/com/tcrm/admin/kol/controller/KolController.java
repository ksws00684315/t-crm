package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kol.service.KolService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KOL management (task 04 §4.3, §5). Permission codes are checked here. A staff account with OWN
 * scope sees only KOLs assigned to them; changing the BD is super-admin only.
 */
@RestController
@RequestMapping("/admin-api/kol/kols")
public class KolController {
  private final KolService kols;

  public KolController(KolService kols) {
    this.kols = kols;
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
}
