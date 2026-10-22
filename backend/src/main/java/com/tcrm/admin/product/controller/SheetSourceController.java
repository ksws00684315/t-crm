package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.product.service.SheetSourceService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product source sheets (ADR-021): configuration is super-administrator only, reading and the "no
 * longer in the sheet" list need product:edit, the list needs product:view.
 *
 * <p>Parameter constraints ({@code @Min}/{@code @Max}) rely on Spring MVC built-in method
 * validation (Framework 6.1+); do not put class-level {@code @Validated} on controllers.
 */
@RestController
@RequestMapping("/admin-api/product/sheet-sources")
public class SheetSourceController {
  private final SheetSourceService sources;

  public SheetSourceController(SheetSourceService sources) {
    this.sources = sources;
  }

  @GetMapping
  @SaCheckPermission("product:view")
  public R<List<SheetSourceVO>> list() {
    return R.ok(sources.list().stream().map(SheetSourceVO::from).toList());
  }

  @PostMapping
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Create source sheet")
  public R<SheetSourceVO> create(@Valid @RequestBody SheetSourceRequest request) {
    return R.ok(SheetSourceVO.from(sources.create(request.toCommand())));
  }

  @PutMapping("/{id}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Edit source sheet")
  public R<SheetSourceVO> update(
      @PathVariable @Min(1) Long id, @Valid @RequestBody SheetSourceRequest request) {
    return R.ok(SheetSourceVO.from(sources.update(id, request.toCommand())));
  }

  @PutMapping("/{id}/status")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Change source sheet status")
  public R<Void> status(
      @PathVariable @Min(1) Long id, @Valid @RequestBody ProductStatusRequest request) {
    sources.updateStatus(id, request.status());
    return R.ok(null);
  }

  @DeleteMapping("/{id}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "PRODUCT", action = "Delete source sheet")
  public R<Void> delete(@PathVariable @Min(1) Long id) {
    sources.delete(id);
    return R.ok(null);
  }

  /** Reads the sheet now; 202 with the import task to follow on the task progress endpoint. */
  @PostMapping("/{id}/reads")
  @SaCheckPermission("product:edit")
  @AuditLog(module = "PRODUCT", action = "Read source sheet")
  public ResponseEntity<R<SheetSourceService.ReadResult>> read(@PathVariable @Min(1) Long id) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(sources.read(id)));
  }

  @GetMapping("/{id}/missing-products")
  @SaCheckPermission("product:edit")
  public R<PageResult<SheetSourceService.MissingDTO>> missing(
      @PathVariable @Min(1) Long id,
      @RequestParam(defaultValue = "1") @Min(1) @Max(PageResult.MAX_PAGE) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return R.ok(sources.missing(id, page, size));
  }
}
