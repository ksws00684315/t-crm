package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-side payout accounts (task 15a §4.4). The list is readable with the review permission or
 * the commission view and shows masked accounts; the detail (full account), the review and the QR
 * download need {@code payout-account:review}. An OWN-scope admin only reaches accounts of KOLs
 * currently assigned to them; anything else is a 404.
 */
@RestController
public class AdminPayoutAccountController {
  private final PayoutAccountService accounts;
  private final CurrentUserContext current;

  public AdminPayoutAccountController(PayoutAccountService accounts, CurrentUserContext current) {
    this.accounts = accounts;
    this.current = current;
  }

  /** Review queue by default (PENDING); {@code status=ALL} lists everything. */
  @GetMapping("/admin-api/commission/payout-accounts")
  @SaCheckPermission(
      value = {"payout-account:review", "commission:view"},
      mode = SaMode.OR)
  public R<PageResult<AdminPayoutAccountVO>> list(@Valid @ModelAttribute PayoutAccountQuery q) {
    return R.ok(accounts.page(current.user(), q.toFilter()).map(AdminPayoutAccountVO::from));
  }

  /** Pending accounts the caller may review; for a future menu badge (task 15a §4.5). */
  @GetMapping("/admin-api/commission/payout-accounts/pending-count")
  @SaCheckPermission(
      value = {"payout-account:review", "commission:view"},
      mode = SaMode.OR)
  public R<PendingCountVO> pendingCount() {
    return R.ok(new PendingCountVO(accounts.pendingCount(current.user())));
  }

  /** Full account for reviewers; every single view is audited. */
  @GetMapping("/admin-api/commission/payout-accounts/{id}")
  @SaCheckPermission("payout-account:review")
  @AuditLog(module = "PayoutAccount", action = "View detail")
  public R<AdminPayoutAccountDetailVO> detail(@PathVariable @Min(1) Long id) {
    return R.ok(AdminPayoutAccountDetailVO.from(accounts.detail(current.user(), id)));
  }

  /** Approve or reject a PENDING account; rejecting needs a reason. */
  @PutMapping("/admin-api/commission/payout-accounts/{id}/review")
  @SaCheckPermission("payout-account:review")
  @AuditLog(module = "PayoutAccount", action = "Review")
  public R<Void> review(@PathVariable @Min(1) Long id, @Valid @RequestBody ReviewRequest r) {
    accounts.review(current.user(), id, r.toAction(), r.normalizedReason(), r.lastSubmittedAt());
    return R.ok(null);
  }

  /**
   * Business-file download (task 15a §4.4): today only attached QR images of accounts the caller
   * could review. The file name never carries account data; the content streams as attachment.
   */
  @GetMapping("/admin-api/files/{id}")
  @SaCheckPermission("payout-account:review")
  @AuditLog(module = "PayoutAccount", action = "Download file")
  public ResponseEntity<byte[]> file(@PathVariable @Min(1) Long id) {
    BizFileService.FileDTO file = accounts.fileForAdmin(current.user(), id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .body(file.content());
  }

  record PendingCountVO(Long count) {}
}
