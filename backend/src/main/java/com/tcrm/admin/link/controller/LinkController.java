package com.tcrm.admin.link.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.link.service.LinkRebuildService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tracking links and the Redis link index (task 06 §5). */
@RestController
@RequestMapping("/admin-api/link")
public class LinkController {
  private final LinkRebuildService rebuilds;

  public LinkController(LinkRebuildService rebuilds) {
    this.rebuilds = rebuilds;
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
