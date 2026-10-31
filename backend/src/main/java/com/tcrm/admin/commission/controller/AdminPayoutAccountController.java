package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.commission.service.PrepayService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-side payout accounts (task 15a §4.4, task 82). The list is readable with the review
 * permission or the commission view and shows masked accounts; the detail (full account) and the QR
 * download need {@code payout-account:review}. Accounts are no longer reviewed (2026-10-29). An
 * OWN-scope admin only reaches accounts of KOLs currently assigned to them; anything else is a 404.
 */
@RestController
public class AdminPayoutAccountController {
  private final com.tcrm.admin.commission.service.WithdrawalService withdrawals;
  private final PayoutAccountService accounts;
  private final CurrentUserContext current;
  private final PrepayService prepays;

  public AdminPayoutAccountController(
      com.tcrm.admin.commission.service.WithdrawalService withdrawals,
      PayoutAccountService accounts,
      CurrentUserContext current,
      PrepayService prepays) {
    this.accounts = accounts;
    this.withdrawals = withdrawals;
    this.current = current;
    this.prepays = prepays;
  }

  /**
   * Accounts of the caller's visible KOLs, every status unless filtered; {@code kolId} gives the
   * exact KOL for the fund dialog (task 82).
   */
  @GetMapping("/admin-api/commission/payout-accounts")
  @SaCheckPermission(
      value = {"payout-account:review", "commission:view"},
      mode = SaMode.OR)
  public R<PageResult<AdminPayoutAccountVO>> list(@Valid @ModelAttribute PayoutAccountQuery q) {
    return R.ok(accounts.page(current.user(), q.toFilter()).map(AdminPayoutAccountVO::from));
  }

  /** Full account for reviewers; every single view is audited. */
  @GetMapping("/admin-api/commission/payout-accounts/{id}")
  @SaCheckPermission("payout-account:review")
  @AuditLog(module = "PayoutAccount", action = "View detail")
  public R<AdminPayoutAccountDetailVO> detail(@PathVariable @Min(1) Long id) {
    return R.ok(AdminPayoutAccountDetailVO.from(accounts.detail(current.user(), id)));
  }

  /**
   * Business-file download (task 15a §4.4): today only attached QR images of accounts the caller
   * could review. The file name never carries account data; the content streams as attachment.
   */
  @GetMapping("/admin-api/files/{id}")
  @SaCheckPermission(
      value = {
        "payout-account:review",
        "prepay:apply",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view",
        "withdrawal:pay"
      },
      mode = SaMode.OR)
  @AuditLog(module = "PayoutAccount", action = "Download file")
  public ResponseEntity<byte[]> file(@PathVariable @Min(1) Long id) {
    var user = current.user();
    BizFileService.AttachedFile attached = accounts.attachedFile(id);
    BizFileService.FileDTO file =
        attached.bizType() == com.tcrm.admin.commission.model.FileBizType.PAYOUT_QR
            ? payoutQrFile(user, id)
            : attached.bizType() == com.tcrm.admin.commission.model.FileBizType.WITHDRAWAL_VOUCHER
                ? withdrawals.voucherFile(user, id)
                : prepays.adminFile(user, id);
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

  private BizFileService.FileDTO payoutQrFile(
      com.tcrm.admin.security.api.AccountDTO user, long fileId) {
    if (user.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN
        && !user.permissions().contains("payout-account:review")) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.AUTH_FORBIDDEN);
    }
    return accounts.fileForAdmin(user, fileId);
  }
}
