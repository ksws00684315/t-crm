package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.service.CopyDailyQuota;
import com.tcrm.admin.product.service.CopyJobQueryDTO;
import com.tcrm.admin.product.service.CopyJobQueryService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.MissingTenantException;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product-task list and line detail (task 38). Retries are a later task. */
@RestController
@RequestMapping("/admin-api/product/copy-jobs")
public class CopyJobController {
  private final CopyJobQueryService jobs;
  private final CopyDailyQuota dailyQuota;
  private final CurrentUserContext current;

  public CopyJobController(
      CopyJobQueryService jobs, CopyDailyQuota dailyQuota, CurrentUserContext current) {
    this.jobs = jobs;
    this.dailyQuota = dailyQuota;
    this.current = current;
  }

  /** Today's Weidian add-item calls of the current company and the daily limit (task 140). */
  @GetMapping("/daily-quota")
  @SaCheckPermission("product:view")
  public R<DailyQuotaVO> dailyQuota() {
    Long tenantId = current.user().tenantId();
    if (tenantId == null) {
      throw new MissingTenantException("copy quota requires a company");
    }
    CopyDailyQuota.Usage usage = dailyQuota.usage(tenantId);
    return R.ok(new DailyQuotaVO(usage.used(), usage.limit()));
  }

  /** Counts, not IDs or amounts, so they stay JSON numbers (spec 03). */
  public record DailyQuotaVO(long used, int limit) {}

  @GetMapping
  @SaCheckPermission("product:view")
  public R<PageResult<CopyJobVO>> list(@Valid @ModelAttribute CopyJobQuery query) {
    return R.ok(jobs.page(query.toFilter()).map(CopyJobVO::from));
  }

  @GetMapping("/by-token/{tokenId}")
  @SaCheckPermission("kol:copy-token")
  public R<PageResult<TokenJobVO>> byToken(
      @PathVariable @Min(1) long tokenId, @Valid @ModelAttribute CopyJobItemPageQuery query) {
    return R.ok(
        jobs.page(
                new CopyJobQueryDTO(
                    null,
                    null,
                    null,
                    query.pageOrDefault(),
                    query.sizeOrDefault(),
                    null,
                    null,
                    tokenId))
            .map(
                job ->
                    new TokenJobVO(
                        Long.toString(job.id()),
                        job.jobCode(),
                        job.status(),
                        job.totalCount(),
                        job.createTime())));
  }

  public record TokenJobVO(
      String id, String jobCode, String status, int totalCount, Instant createTime) {}

  @GetMapping("/{id}/items")
  @SaCheckPermission("product:view")
  public R<PageResult<CopyJobItemVO>> items(
      @PathVariable @Min(1) long id, @Valid @ModelAttribute CopyJobItemPageQuery query) {
    return R.ok(
        jobs.items(
                id,
                query.pageOrDefault(),
                query.sizeOrDefault(),
                query.statusOrder() == null
                    ? null
                    : query.statusOrder() == CopyJobItemPageQuery.StatusOrder.ASCENDING)
            .map(CopyJobItemVO::from));
  }
}
