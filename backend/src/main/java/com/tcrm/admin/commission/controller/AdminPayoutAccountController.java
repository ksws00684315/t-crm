package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.CommissionWithdrawalService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
  private final PrepayService prepays;
  private final BizFileService files;
  private final CommissionWithdrawalService withdrawals;

  public AdminPayoutAccountController(
      PayoutAccountService accounts,
      CurrentUserContext current,
      PrepayService prepays,
      BizFileService files,
      CommissionWithdrawalService withdrawals) {
    this.accounts = accounts;
    this.current = current;
    this.prepays = prepays;
    this.files = files;
    this.withdrawals = withdrawals;
  }

  public AdminPayoutAccountController(
      PayoutAccountService accounts, CurrentUserContext current, PrepayService prepays) {
    this(accounts, current, prepays, null, null);
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
  @SaCheckPermission(
      value = {
        "payout-account:review",
        "prepay:apply",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view"
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
                ? withdrawalVoucherFile(user, id, attached)
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

  @PostMapping("/admin-api/files")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "CommissionWithdrawal", action = "Upload payment voucher")
  public R<FileUploadVO> uploadWithdrawalVoucher(
      @RequestParam("bizType") com.tcrm.admin.commission.model.FileBizType bizType,
      @RequestParam("file") MultipartFile file)
      throws IOException {
    var admin = current.user();
    if (admin.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.AUTH_FORBIDDEN);
    }
    if (bizType != com.tcrm.admin.commission.model.FileBizType.WITHDRAWAL_VOUCHER) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_VALIDATION_FAILED);
    }
    long fileId =
        files.uploadWithdrawalVoucher(
            admin.tenantId(), admin.id(), file.getOriginalFilename(), file.getBytes());
    return R.ok(new FileUploadVO(Long.toString(fileId)));
  }

  record PendingCountVO(Long count) {}

  record FileUploadVO(String fileId) {}

  private BizFileService.FileDTO withdrawalVoucherFile(
      com.tcrm.admin.security.api.AccountDTO user,
      long fileId,
      BizFileService.AttachedFile attached) {
    if (user.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN || attached.bizId() == null) {
      throw new com.tcrm.admin.shared.BusinessException(
          com.tcrm.admin.shared.ErrorCode.COMMON_NOT_FOUND);
    }
    return withdrawals.adminVoucher(user, fileId);
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
