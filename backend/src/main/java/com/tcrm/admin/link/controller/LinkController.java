package com.tcrm.admin.link.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.link.service.LinkRebuildService;
import com.tcrm.admin.link.service.LinkService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tracking links and the Redis link index (task 06 §5). Roles are checked here; the BD data scope
 * (OPERATOR + OWN sees only their own KOLs, 403 otherwise) is enforced by the service.
 */
@RestController
@RequestMapping("/admin-api/link")
public class LinkController {
  private final LinkService links;
  private final LinkRebuildService rebuilds;

  public LinkController(LinkService links, LinkRebuildService rebuilds) {
    this.links = links;
    this.rebuilds = rebuilds;
  }

  /** Links of one KOL ordered by original product code, with 30-day click counts. */
  @GetMapping("/links")
  @SaCheckRole(
      value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
      mode = SaMode.OR)
  public R<PageResult<LinkVO>> list(@Valid @ModelAttribute LinkQuery q) {
    return R.ok(links.page(q.toFilter()).map(LinkVO::from));
  }

  /** Starts a full rebuild of the link index: 202, or 409 LINK_REBUILD_RUNNING. SUPER_ADMIN. */
  @PostMapping("/index-rebuilds")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "LINK", action = "Rebuild link index")
  public ResponseEntity<R<Void>> rebuild() {
    rebuilds.trigger();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(null));
  }
}
