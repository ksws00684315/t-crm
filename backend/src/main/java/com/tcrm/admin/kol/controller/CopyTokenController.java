package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.kol.service.CopyTokenService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Copy-token management for the admin UI (task 17a §4.4). Permission {@code kol:copy-token}; OWN
 * scope sees only tokens of KOLs assigned to the caller.
 */
@RestController
@RequestMapping("/admin-api/kol/copy-tokens")
public class CopyTokenController {
  private final CopyTokenService tokens;

  public CopyTokenController(CopyTokenService tokens) {
    this.tokens = tokens;
  }

  @GetMapping
  @SaCheckPermission("kol:copy-token")
  public R<PageResult<CopyTokenVO>> list(@Valid @ModelAttribute CopyTokenQuery query) {
    return R.ok(tokens.page(query.toFilter()).map(CopyTokenVO::from));
  }

  @PostMapping
  @SaCheckPermission("kol:copy-token")
  @AuditLog(module = "KOL", action = "Create copy token")
  public R<CreatedCopyTokenVO> create(@Valid @RequestBody CreateCopyTokenRequest request) {
    return R.ok(CreatedCopyTokenVO.from(tokens.create(request.toCommand())));
  }

  @PostMapping("/{id}/reveal")
  @SaCheckPermission("kol:copy-token")
  @AuditLog(module = "KOL", action = "Reveal copy token")
  public R<RevealCopyTokenVO> reveal(@PathVariable @Min(1) Long id) {
    return R.ok(new RevealCopyTokenVO(tokens.reveal(id)));
  }

  @DeleteMapping("/{id}")
  @SaCheckPermission("kol:copy-token")
  @AuditLog(module = "KOL", action = "Revoke copy token")
  public R<Void> revoke(@PathVariable @Min(1) Long id) {
    tokens.revoke(id);
    return R.ok(null);
  }
}
