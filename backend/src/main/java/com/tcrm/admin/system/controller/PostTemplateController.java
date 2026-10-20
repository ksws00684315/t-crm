package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.PostTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin-api/system/post-templates")
@SaCheckRole("SUPER_ADMIN")
public class PostTemplateController {
  private final PostTemplateService templates;

  public PostTemplateController(PostTemplateService templates) {
    this.templates = templates;
  }

  @GetMapping
  public R<List<PostTemplateService.PostTemplateView>> list() {
    return R.ok(templates.list());
  }

  @PutMapping("/{postTemplate}")
  @AuditLog(module = "System", action = "Update post template")
  public R<Void> update(
      @PathVariable PostTemplate postTemplate, @Valid @RequestBody UpdateRequest r) {
    templates.update(postTemplate, r.dataScope(), r.permissions());
    return R.ok(null);
  }

  @PostMapping("/{postTemplate}/reset")
  @AuditLog(module = "System", action = "Reset post template")
  public R<Void> reset(@PathVariable PostTemplate postTemplate) {
    templates.reset(postTemplate);
    return R.ok(null);
  }

  public record UpdateRequest(@NotNull DataScope dataScope, @NotNull List<String> permissions) {}
}
