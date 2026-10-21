package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.service.CopyJobQueryService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product-task list and line detail (task 38). Retries are a later task. */
@Validated
@RestController
@RequestMapping("/admin-api/product/copy-jobs")
public class CopyJobController {
  private final CopyJobQueryService jobs;

  public CopyJobController(CopyJobQueryService jobs) {
    this.jobs = jobs;
  }

  @GetMapping
  @SaCheckPermission("product:view")
  public R<PageResult<CopyJobVO>> list(@Valid @ModelAttribute CopyJobQuery query) {
    return R.ok(jobs.page(query.toFilter()).map(CopyJobVO::from));
  }

  @GetMapping("/{id}/items")
  @SaCheckPermission("product:view")
  public R<PageResult<CopyJobItemVO>> items(
      @PathVariable @Min(1) long id, @Valid @ModelAttribute CopyJobItemPageQuery query) {
    return R.ok(
        jobs.items(id, query.pageOrDefault(), query.sizeOrDefault()).map(CopyJobItemVO::from));
  }
}
