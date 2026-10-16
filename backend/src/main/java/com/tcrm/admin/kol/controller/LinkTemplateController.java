package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.kol.service.LinkTemplateService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent link template management (task 11 §4.2). Reading needs the same roles as editing a KOL (the
 * profile picks a template); writing is SUPER_ADMIN only. Until task 10a lands, the role
 * annotations follow the current convention.
 *
 * <p>The table itself lives in the {@code kol} module because the KOL profile validates and
 * references templates; the {@code link} module reads the resolved template through {@code kol.api}
 * (modules must not depend on each other in both directions).
 */
@RestController
@RequestMapping("/admin-api/link/templates")
public class LinkTemplateController {
  private final LinkTemplateService templates;

  public LinkTemplateController(LinkTemplateService templates) {
    this.templates = templates;
  }

  /** All templates of the current company, disabled included, ordered by sort then id. */
  @GetMapping
  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR"},
      mode = SaMode.OR)
  public R<List<LinkTemplateVO>> list() {
    return R.ok(templates.list().stream().map(LinkTemplateVO::from).toList());
  }

  @PostMapping
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "LINK_TEMPLATE", action = "Create")
  public R<LinkTemplateVO> create(@Valid @RequestBody CreateLinkTemplateRequest r) {
    return R.ok(LinkTemplateVO.from(templates.create(r.toCommand())));
  }

  /** Changes name, URL and sort order; the code cannot change. */
  @PutMapping("/{id}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "LINK_TEMPLATE", action = "Edit")
  public R<Void> edit(
      @PathVariable @Min(1) Long id, @Valid @RequestBody EditLinkTemplateRequest r) {
    templates.update(id, r.toCommand());
    return R.ok(null);
  }

  /** 1 enabled, 0 disabled. */
  @PutMapping("/{id}/status")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "LINK_TEMPLATE", action = "Change status")
  public R<Void> status(
      @PathVariable @Min(1) Long id, @Valid @RequestBody TemplateStatusRequest r) {
    templates.updateStatus(id, r.status());
    return R.ok(null);
  }

  /** 1 enabled, 0 disabled. */
  record TemplateStatusRequest(@NotNull @Min(0) @Max(1) Integer status) {}
}
