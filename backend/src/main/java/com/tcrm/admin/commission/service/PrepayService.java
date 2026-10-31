package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.PrepayMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.KolLedgerRow;
import com.tcrm.admin.commission.model.KolPaidPrepayRow;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PrepayApplicationColumns;
import com.tcrm.admin.commission.model.PrepayDeductionRow;
import com.tcrm.admin.commission.model.PrepayHead;
import com.tcrm.admin.commission.model.PrepayHistoryRow;
import com.tcrm.admin.commission.model.PrepayPageQuery;
import com.tcrm.admin.commission.model.PrepayPaymentRow;
import com.tcrm.admin.commission.model.PrepayRecipientRow;
import com.tcrm.admin.commission.model.PrepayWrite;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.AccountNameDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepay creation, review and payment. Company isolation is the tenant plugin's job. */
@Service
public class PrepayService {
  private static final ZoneId NUMBER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final List<String> STATUSES =
      List.of(
          "WAITING_DETAILS",
          "PENDING_OPERATIONS_REVIEW",
          "PENDING_FINANCE_REVIEW",
          "APPROVED",
          "PAID",
          "REJECTED",
          "CANCELLED");
  private final PrepayMapper prepays;
  private final KolApi kols;
  private final AccountNameApi accountNames;
  private final PayoutAccountMapper payoutAccounts;
  private final BizFileService files;
  private final SecretCipher cipher;
  private final CommissionWalletService wallet;
  private final ExternalAuditApi audit;
  private final Clock clock;

  @Autowired
  public PrepayService(
      PrepayMapper prepays,
      KolApi kols,
      AccountNameApi accountNames,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      CommissionWalletService wallet,
      ExternalAuditApi audit) {
    this(
        prepays,
        kols,
        accountNames,
        payoutAccounts,
        files,
        cipher,
        wallet,
        audit,
        Clock.systemUTC());
  }

  PrepayService(
      PrepayMapper prepays,
      KolApi kols,
      AccountNameApi accountNames,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      CommissionWalletService wallet,
      ExternalAuditApi audit,
      Clock clock) {
    this.prepays = prepays;
    this.kols = kols;
    this.accountNames = accountNames;
    this.payoutAccounts = payoutAccounts;
    this.files = files;
    this.cipher = cipher;
    this.wallet = wallet;
    this.audit = audit;
    this.clock = clock;
  }

  public record PayoutOption(
      Long id,
      String type,
      String currency,
      String network,
      String maskedAccount,
      String holderName) {}

  public record PrepayRow(
      Long id,
      String prepayNo,
      String financeTaskType,
      Long financeTaskId,
      Long kolId,
      String kolCode,
      String kolName,
      Long applicantId,
      String applicantName,
      String amountCny,
      String currency,
      String status,
      Instant createdAt) {
    @com.fasterxml.jackson.annotation.JsonProperty("applicantRole")
    public String applicantRole() {
      return "BUSINESS";
    }
  }

  public record PrepayApplication(String requestId, Long kolId, String amountCny) {}

  public record ApplicationDetails(
      String reason,
      LocalDate expectedPayDate,
      Long payoutAccountId,
      Boolean deductFromCommission,
      String recipientText) {
    public ApplicationDetails(
        String reason,
        LocalDate expectedPayDate,
        Long payoutAccountId,
        Boolean deductFromCommission) {
      this(reason, expectedPayDate, payoutAccountId, deductFromCommission, null);
    }
  }

  public record UploadedFile(String fileName, byte[] content) {}

  public record PaymentCommand(
      String requestId, String payAmountCny, String payTime, String payMethod, String payRemark) {}

  public record Filter(String prepayNo, String kolCode, String status, int page, int size) {}

  public record Approval(
      String decision,
      Long operatorId,
      Instant time,
      String reason,
      String operatorName,
      String operatorRole) {}

  public record Payment(
      String payAmountCny,
      String payCurrency,
      Instant payTime,
      String payMethod,
      String payRemark,
      Long voucherFileId,
      Long operatorId,
      String operatorName,
      String operatorRole) {}

  public record Timeline(
      String action,
      Long operatorId,
      Instant time,
      String reason,
      String operatorName,
      String operatorRole) {}

  public record Deduction(
      String postedAmountCny, String recoveredAmountCny, String remainingAmountCny) {}

  public record PrepayDetail(
      Long id,
      String prepayNo,
      String financeTaskType,
      Long financeTaskId,
      Long kolId,
      String kolCode,
      String kolName,
      Long applicantId,
      String applicantName,
      String amountCny,
      String currency,
      String status,
      Instant createdAt,
      String reason,
      LocalDate expectedPayDate,
      Boolean deductFromCommission,
      Long payoutAccountId,
      PayoutOption payoutAccountSnapshot,
      List<BizFileService.FileMeta> attachments,
      Approval approval,
      Payment payment,
      List<Timeline> timeline,
      Deduction deduction,
      String walletBalanceCny) {
    @com.fasterxml.jackson.annotation.JsonProperty("applicantRole")
    public String applicantRole() {
      return "BUSINESS";
    }
  }

  public record Recipient(
      String type, String currency, String network, String holderName, String account) {}

  /** Paid prepay projected for the signed-in creator. */
  public record PaidPrepay(
      Long id,
      String prepayNo,
      String status,
      String payAmountCny,
      String payCurrency,
      Instant payTime,
      String payMethod,
      Long voucherFileId,
      Boolean deductFromCommission,
      Deduction deduction,
      String walletBalanceCny) {}

  /** One creator-ledger line: the payment itself, or the commission deduction. */
  public record LedgerRow(
      Long entryId,
      Long prepayId,
      String prepayNo,
      String entryType,
      String amountCny,
      Instant occurredAt,
      String balanceAfterCny) {}

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail apply(AccountDTO user, PrepayApplication request) {
    requirePermission(user, "prepay:apply");
    long tenantId = requireTenant(user);
    UUID requestId = parseUuid(request.requestId());
    long kolId = requirePositive(request.kolId());
    BigDecimal amount = money(request.amountCny());
    return TenantScope.callCompany(tenantId, () -> applyInCompany(user, requestId, kolId, amount));
  }

  private PrepayDetail applyInCompany(
      AccountDTO user, UUID requestId, long kolId, BigDecimal amount) {
    PrepayRow existing = findByRequest(requestId);
    if (existing != null) {
      return returnExistingRequest(user, existing, kolId, amount);
    }
    if (!kols.lockKol(kolId)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    existing = findByRequest(requestId);
    if (existing != null) {
      return returnExistingRequest(user, existing, kolId, amount);
    }
    KolRefDTO kol = requireKol(kolId);
    requireKolScope(user, kol);
    if (prepays.findActiveId(kolId) != null) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    Instant now = now();
    long id = IdWorker.getId();
    String prepayNo = nextPrepayNo(id, now);
    int created;
    try {
      created =
          prepays.insertWaiting(
              new PrepayWrite.Insert(
                  id, kolId, prepayNo, requestId, user.id(), amount, user.id(), now));
    } catch (DuplicateKeyException exception) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    if (created == 0) {
      PrepayRow duplicate = findByRequest(requestId);
      if (duplicate == null) {
        throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
      }
      return returnExistingRequest(user, duplicate, kolId, amount);
    }
    insertHistory(id, "SUBMITTED", user, "BUSINESS", null, now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "Submit application",
        "POST",
        "/admin-api/commission/prepays",
        "{\"prepayNo\":\"" + prepayNo + "\",\"kolId\":\"" + kolId + "\"}");
    return detail(user, id);
  }

  private PrepayDetail returnExistingRequest(
      AccountDTO user, PrepayRow existing, long kolId, BigDecimal amount) {
    if (!existing.kolId().equals(kolId)
        || new BigDecimal(existing.amountCny()).compareTo(amount) != 0) {
      throw new BusinessException(ErrorCode.PREPAY_REQUEST_CONFLICT);
    }
    return detail(user, existing.id());
  }

  /**
   * Saves applicant-supplied fields while the task is waiting; null deduction is never inferred.
   */
  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail saveApplicationDetails(
      AccountDTO user, long id, ApplicationDetails details, List<UploadedFile> attachments) {
    requirePermission(user, "prepay:apply");
    long tenantId = requireTenant(user);
    validateApplicationDetails(details);
    return TenantScope.callCompany(
        tenantId, () -> saveDetailsInCompany(user, id, details, attachments));
  }

  private PrepayDetail saveDetailsInCompany(
      AccountDTO user, long id, ApplicationDetails details, List<UploadedFile> attachments) {
    PrepayRow row = requireTaskLocked(id);
    if (!"WAITING_DETAILS".equals(status(id)) || !row.applicantId().equals(user.id())) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    requireKolScope(user, requireKol(row.kolId()));
    KolRefDTO kol = requireKol(row.kolId());
    boolean manual = details.recipientText() != null;
    PayoutAccountDO account =
        manual ? null : approvedAccount(user.tenantId(), row.kolId(), details);
    Instant now = now();
    List<BizFileService.FileMeta> existingFiles = files.prepayAttachments(id);
    List<UploadedFile> additions = attachments == null ? List.of() : List.copyOf(attachments);
    if (existingFiles.size() + additions.size() > 5) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    for (UploadedFile attachment : additions) {
      files.uploadPrepayAdmin(
          user.tenantId(),
          user.id(),
          id,
          FileBizType.PREPAY_ATTACHMENT,
          attachment.fileName(),
          attachment.content());
    }
    String cipherText =
        manual ? cipher.encrypt(details.recipientText()) : account.getAccountCipher();
    prepays.completeDetails(
        detailsCommand(id, details, account, manual, cipherText, user.id(), now));
    insertHistory(id, "DETAILS_COMPLETED", user, "BUSINESS", null, now);
    insertOutbox(id, "SUBMITTED", row.prepayNo(), now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "Complete application details",
        "PUT",
        "/admin-api/commission/prepays/" + id + "/application-details",
        "{\"prepayId\":\"" + id + "\",\"kolId\":\"" + kol.id() + "\"}");
    return detail(user, id);
  }

  public PrepayDetail activeApplication(AccountDTO user, long kolId) {
    requirePermission(user, "prepay:apply");
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(
        tenantId,
        () -> {
          KolRefDTO kol = requireKol(kolId);
          requireKolScope(user, kol);
          Long id = prepays.findLatestActiveId(kolId);
          return id == null ? null : detail(user, id);
        });
  }

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail approval(AccountDTO user, long id, String decision, String reason) {
    requireAnyPermission(user, "prepay:review", "prepay:finance-review");
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(tenantId, () -> approvalInCompany(user, id, decision, reason));
  }

  private PrepayDetail approvalInCompany(AccountDTO user, long id, String decision, String reason) {
    requireTaskLocked(id);
    PrepayDetail detail = detail(user, id);
    boolean operations = "PENDING_OPERATIONS_REVIEW".equals(detail.status());
    boolean finance = "PENDING_FINANCE_REVIEW".equals(detail.status());
    if (!operations && !finance) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    requirePermission(user, operations ? "prepay:review" : "prepay:finance-review");
    // Keep prepay:review on BD template; still block the applicant from reviewing their own task.
    if (detail.applicantId() != null && detail.applicantId().equals(user.id())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    boolean approve = "APPROVE".equals(decision);
    if (!approve && !"REJECT".equals(decision)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (approve && !detailsComplete(detail)) {
      throw new BusinessException(ErrorCode.PREPAY_DETAILS_REQUIRED);
    }
    String normalizedReason = reason == null ? null : reason.strip();
    if (normalizedReason != null && normalizedReason.length() > 1000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (!approve && (normalizedReason == null || normalizedReason.isEmpty())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Instant now = now();
    String next = approve ? (operations ? "PENDING_FINANCE_REVIEW" : "APPROVED") : "REJECTED";
    int changed =
        prepays.decide(
            new PrepayWrite.Decision(
                id, next, detail.status(), user.id(), now, approve ? null : normalizedReason));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    String action =
        approve ? (operations ? "OPERATIONS_APPROVED" : "FINANCE_APPROVED") : "REJECTED";
    insertHistory(
        id,
        action,
        user,
        operations ? "OPERATIONS_REVIEW" : "FINANCE_REVIEW",
        normalizedReason,
        now);
    if (!operations || !approve) {
      insertOutbox(id, approve ? "APPROVED" : action, detail.prepayNo(), now);
    }
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        approve ? "Approve" : "Reject",
        "PUT",
        "/admin-api/commission/prepays/" + id + "/approval",
        "{\"prepayNo\":\"" + detail.prepayNo() + "\",\"decision\":\"" + decision + "\"}");
    return detail(user, id);
  }

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail cancel(AccountDTO user, long id, String reason) {
    requirePermission(user, "prepay:apply");
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(tenantId, () -> cancelInCompany(user, id, reason));
  }

  private PrepayDetail cancelInCompany(AccountDTO user, long id, String reason) {
    requireTaskLocked(id);
    PrepayDetail detail = detail(user, id);
    if (!"WAITING_DETAILS".equals(detail.status()) || !detail.applicantId().equals(user.id())) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    requireKolScope(user, requireKol(detail.kolId()));
    Instant now = now();
    String normalizedReason = reason == null || reason.isBlank() ? null : reason.strip();
    if (normalizedReason != null && normalizedReason.length() > 1000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    int changed = prepays.cancel(new PrepayWrite.Cancel(id, user.id(), now, normalizedReason));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    insertHistory(id, "CANCELLED", user, "BUSINESS", normalizedReason, now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "Cancel application",
        "PUT",
        "/admin-api/commission/prepays/" + id + "/cancel",
        "{\"prepayNo\":\"" + detail.prepayNo() + "\"}");
    return detail(user, id);
  }

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail pay(AccountDTO user, long id, PaymentCommand request, UploadedFile voucher) {
    requirePermission(user, "prepay:pay");
    long tenantId = requireTenant(user);
    UUID requestId = parseUuid(request.requestId());
    BigDecimal amount = money(request.payAmountCny());
    Instant payTime = payTime(request.payTime());
    String method = request.payMethod() == null ? "" : request.payMethod().strip();
    String remark = request.payRemark() == null ? null : request.payRemark().strip();
    if (method.isEmpty() || method.length() > 100 || (remark != null && remark.length() > 1000)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (voucher == null || voucher.content() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String fingerprint =
        paymentFingerprint(id, requestId, amount, payTime, method, remark, voucher.content());
    return TenantScope.callCompany(
        tenantId,
        () ->
            payInCompany(
                user, id, requestId, amount, payTime, method, remark, fingerprint, voucher));
  }

  private PrepayDetail payInCompany(
      AccountDTO user,
      long id,
      UUID requestId,
      BigDecimal amount,
      Instant payTime,
      String method,
      String remark,
      String fingerprint,
      UploadedFile voucher) {
    if (paymentRecorded(requestId, fingerprint)) {
      return detail(user, id);
    }
    PrepayRow row = requireTaskLocked(id);
    if (paymentRecorded(requestId, fingerprint)) {
      return detail(user, id);
    }
    PrepayDetail task = detail(user, id);
    if (!"APPROVED".equals(task.status())) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    if (!detailsComplete(task)) {
      throw new BusinessException(ErrorCode.PREPAY_DETAILS_REQUIRED);
    }
    BigDecimal requestedAmount = new BigDecimal(task.amountCny());
    if (requestedAmount.compareTo(amount) != 0 && (remark == null || remark.isBlank())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Long voucherId =
        files.uploadPrepayAdmin(
            user.tenantId(),
            user.id(),
            id,
            FileBizType.PREPAY_VOUCHER,
            voucher.fileName(),
            voucher.content());
    Instant now = now();
    prepays.insertPayment(
        new PrepayWrite.Payment(
            IdWorker.getId(),
            id,
            requestId,
            fingerprint,
            amount,
            payTime,
            method,
            remark,
            voucherId,
            user.id(),
            now,
            user.nickname(),
            "PAYMENT"));
    int changed = prepays.markPaid(new PrepayWrite.Paid(id, user.id(), now));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    if (Boolean.TRUE.equals(task.deductFromCommission())) {
      wallet.postPrepayDeduction(user.tenantId(), row.kolId(), id, amount, payTime, user.id());
    }
    insertHistory(id, "PAID", user, "PAYMENT", null, now);
    insertOutbox(id, "PAID", row.prepayNo(), now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "Record payment",
        "PUT",
        "/admin-api/commission/prepays/" + id + "/paid",
        "{\"prepayNo\":\"" + row.prepayNo() + "\",\"voucherFileId\":\"" + voucherId + "\"}");
    return detail(user, id);
  }

  @Transactional(rollbackFor = Exception.class)
  public Recipient recipient(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    return TenantScope.callCompany(tenantId, () -> recipientInCompany(user, id));
  }

  private Recipient recipientInCompany(AccountDTO user, long id) {
    PrepayRow row = findRow(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolRefDTO kol = requireKol(row.kolId());
    if (!hasFinanceAccess(user)) {
      requireKolScope(user, kol);
    }
    if (prepays.lockById(id) == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    PrepayRecipientRow stored = prepays.findRecipient(id);
    if (stored == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    audit.recordTenantOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "View recipient",
        "/admin-api/commission/prepays/" + id + "/recipient",
        "{\"prepayNo\":\"" + stored.prepayNo() + "\"}");
    return new Recipient(
        stored.payoutAccountType(),
        stored.payoutCurrency(),
        stored.payoutNetwork(),
        stored.payoutHolderName(),
        cipher.decrypt(stored.payoutAccountCipher()));
  }

  public BizFileService.FileDTO adminFile(AccountDTO user, long fileId) {
    requireDetailPermission(user);
    long tenantId = requireTenant(user);
    return TenantScope.callCompany(tenantId, () -> adminFileInCompany(user, fileId));
  }

  private BizFileService.FileDTO adminFileInCompany(AccountDTO user, long fileId) {
    BizFileService.AttachedFile file = files.attachedFile(fileId);
    if ((file.bizType() != FileBizType.PREPAY_ATTACHMENT
            && file.bizType() != FileBizType.PREPAY_VOUCHER)
        || file.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    PrepayRow row = findRow(file.bizId());
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (!hasFinanceAccess(user)) {
      requireKolScope(user, requireKol(row.kolId()));
    }
    return file.download();
  }

  public BizFileService.FileDTO kolFile(long kolId, long fileId) {
    BizFileService.AttachedFile file = files.attachedFile(fileId);
    if (file.bizType() != FileBizType.PREPAY_VOUCHER || file.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    Long prepayKol = prepays.paidKolId(file.bizId(), fileId);
    if (prepayKol == null || prepayKol != kolId) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return file.download();
  }

  public PrepayDetail detail(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    return TenantScope.callCompany(tenantId, () -> detailInCompany(user, id));
  }

  private PrepayDetail detailInCompany(AccountDTO user, long id) {
    PrepayHead head = prepays.findById(id);
    if (head == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolRefDTO kol = requireKol(head.kolId());
    if (!hasFinanceAccess(user)) {
      requireKolScope(user, kol);
    }
    PrepayApplicationColumns values = application(id);
    PrepayHistoryRow approval = prepays.latestApproval(id);
    PrepayPaymentRow payment = prepays.findPayment(id);
    List<Timeline> timeline = prepays.timeline(id).stream().map(this::timeline).toList();
    String applicantName = applicantName(head.applicantId());
    return new PrepayDetail(
        head.id(),
        head.prepayNo(),
        "PREPAY",
        head.id(),
        head.kolId(),
        kol.kolCode(),
        kol.name(),
        head.applicantId(),
        applicantName,
        money(head.amountCny()),
        "CNY",
        head.status(),
        head.createTime(),
        values.reason(),
        values.expectedPayDate(),
        values.deductFromCommission(),
        values.payoutAccountId(),
        snapshot(values),
        files.prepayAttachments(id),
        approval == null ? null : approval(approval),
        payment == null ? null : payment(payment),
        timeline,
        deduction(id, head.status(), values.deductFromCommission()),
        money(wallet.currentBalance(user.tenantId(), head.kolId())));
  }

  public PageResult<PrepayRow> page(AccountDTO user, Filter filter) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    if (filter.page() < 1 || filter.size() < 1 || filter.size() > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String status = stripToNull(filter.status());
    if (status != null && !STATUSES.contains(status)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(tenantId, () -> pageInCompany(user, filter, status));
  }

  private PageResult<PrepayRow> pageInCompany(AccountDTO user, Filter filter, String status) {
    String prepayNo = stripToNull(filter.prepayNo());
    String kolCode = stripToNull(filter.kolCode());
    Long kolId = null;
    if (kolCode != null) {
      KolRefDTO match = kols.getByCode(kolCode);
      if (match == null || match.kolCode() == null || !match.kolCode().equals(kolCode)) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
      kolId = match.id();
    }
    List<Long> ownIds = null;
    if (!hasFinanceAccess(user) && user.ownScope()) {
      ownIds = kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).distinct().toList();
      if (ownIds.isEmpty()) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
    }
    PrepayPageQuery query =
        new PrepayPageQuery(
            prepayNo,
            kolId,
            status,
            ownIds,
            filter.size(),
            (long) (filter.page() - 1) * filter.size());
    Long total = prepays.count(query);
    List<PrepayHead> heads = prepays.page(query);
    Map<Long, KolRefDTO> kolById =
        kolsById(heads.stream().map(PrepayHead::kolId).distinct().toList());
    Map<Long, String> names =
        namesById(heads.stream().map(PrepayHead::applicantId).distinct().toList());
    List<PrepayRow> rows =
        heads.stream()
            .map(head -> toRow(head, kolById.get(head.kolId()), names.get(head.applicantId())))
            .toList();
    return new PageResult<>(rows, total == null ? 0L : total, filter.page(), filter.size());
  }

  @Transactional(readOnly = true)
  public byte[] export(AccountDTO user, Filter filter) {
    requireTenant(user);
    // data:export AND the same list permission gate as page()/detail (design §4.12 / API table).
    if (user.role() != Role.SUPER_ADMIN && !user.permissions().contains("data:export")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    requireDetailPermission(user);
    return TenantScope.callCompany(user.tenantId(), () -> exportInCompany(user, filter));
  }

  private byte[] exportInCompany(AccountDTO user, Filter filter) {
    StringBuilder csv =
        new StringBuilder("\uFEFF预付编号,达人编号,达人名称,申请人ID,申请金额(CNY),状态,付款金额(CNY),付款时间,付款方式,脱敏收款账户\r\n");
    PageResult<PrepayRow> first =
        page(user, new Filter(filter.prepayNo(), filter.kolCode(), filter.status(), 1, 100));
    if (first.total() > 100000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    appendExportRows(csv, first.items());
    for (int page = 2; (long) (page - 1) * 100 < first.total(); page++) {
      appendExportRows(
          csv,
          page(user, new Filter(filter.prepayNo(), filter.kolCode(), filter.status(), page, 100))
              .items());
    }
    audit.recordTenantOperation(
        user.tenantId(),
        user.id(),
        "Prepay",
        "Export tasks",
        "/admin-api/commission/prepays/export",
        "{\"rowCount\":" + first.total() + "}");
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Transactional(readOnly = true)
  public PageResult<PaidPrepay> listPaid(long tenantId, long kolId, int page, int size) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          Long total = prepays.countPaid(kolId);
          List<PaidPrepay> items = new ArrayList<>();
          for (Long id : prepays.listPaidIds(kolId, size, (long) (page - 1) * size)) {
            items.add(toPaid(prepays.findPaid(kolId, id)));
          }
          return new PageResult<>(items, total == null ? 0L : total, page, size);
        });
  }

  @Transactional(readOnly = true)
  public PaidPrepay paid(long tenantId, long kolId, long id) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          Long count = prepays.countPaidOne(kolId, id);
          if (count == null || count != 1L) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          KolPaidPrepayRow row = prepays.findPaid(kolId, id);
          if (row == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          return toPaid(row);
        });
  }

  @Transactional(readOnly = true)
  public PageResult<LedgerRow> ledger(long tenantId, long kolId, int page, int size) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          long total =
              nz(prepays.countPaidPayments(kolId)) + nz(prepays.countPaidDeductions(kolId));
          List<LedgerRow> rows =
              prepays.listLedger(kolId, size, (long) (page - 1) * size).stream()
                  .map(this::ledgerRow)
                  .toList();
          return new PageResult<>(rows, total, page, size);
        });
  }

  private PaidPrepay toPaid(KolPaidPrepayRow row) {
    Deduction deduction = null;
    if (Boolean.TRUE.equals(row.deductFromCommission()) && row.deductionId() != null) {
      BigDecimal posted = row.payAmountCny();
      BigDecimal recovered = nz(prepays.sumRecovered(row.deductionId()));
      deduction =
          new Deduction(
              posted.setScale(2).toPlainString(),
              recovered.setScale(2).toPlainString(),
              posted.subtract(recovered).setScale(2).toPlainString());
    }
    BigDecimal balance = row.balanceCny();
    return new PaidPrepay(
        row.id(),
        row.prepayNo(),
        "PAID",
        row.payAmountCny().setScale(2).toPlainString(),
        "CNY",
        row.payTime(),
        row.payMethod(),
        row.voucherFileId(),
        row.deductFromCommission(),
        deduction,
        balance == null ? "0.00" : balance.setScale(2).toPlainString());
  }

  private LedgerRow ledgerRow(KolLedgerRow row) {
    return new LedgerRow(
        row.entryId(),
        row.prepayId(),
        row.prepayNo(),
        row.entryType(),
        row.amountCny().setScale(2).toPlainString(),
        row.occurredAt(),
        row.balanceAfterCny() == null ? null : row.balanceAfterCny().setScale(2).toPlainString());
  }

  private void appendExportRows(StringBuilder csv, List<PrepayRow> rows) {
    for (PrepayRow row : rows) {
      PrepayPaymentRow payment = prepays.findPayment(row.id());
      String[] fields = {
        row.prepayNo(),
        row.kolCode(),
        row.kolName(),
        row.applicantId().toString(),
        row.amountCny(),
        row.status(),
        payment == null ? "" : money(payment.payAmountCny()),
        payment == null ? "" : payment.payTime().toString(),
        payment == null ? "" : payment.payMethod(),
        maskedAccount(row.id())
      };
      for (int index = 0; index < fields.length; index++) {
        if (index > 0) {
          csv.append(',');
        }
        csv.append(csvCell(fields[index]));
      }
      csv.append("\r\n");
    }
  }

  private String maskedAccount(long id) {
    String masked = prepays.maskedAccount(id);
    return masked == null ? "" : masked;
  }

  private static String csvCell(String raw) {
    String value = raw == null ? "" : raw;
    int firstMeaningful = 0;
    while (firstMeaningful < value.length()
        && Character.isWhitespace(value.charAt(firstMeaningful))) {
      firstMeaningful++;
    }
    if (firstMeaningful < value.length() && "=+-@".indexOf(value.charAt(firstMeaningful)) >= 0) {
      value = "'" + value;
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private void validateApplicationDetails(ApplicationDetails details) {
    if (details == null
        || details.reason() == null
        || details.reason().isBlank()
        || details.reason().strip().length() > 1000
        || details.expectedPayDate() == null
        || (details.payoutAccountId() == null) == (details.recipientText() == null)
        || (details.payoutAccountId() != null && details.payoutAccountId() <= 0)
        || (details.recipientText() != null
            && (details.recipientText().isBlank() || details.recipientText().length() > 1000))
        || details.deductFromCommission() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private PayoutAccountDO approvedAccount(long tenantId, long kolId, ApplicationDetails details) {
    PayoutAccountDO account = payoutAccounts.selectById(details.payoutAccountId());
    if (account == null
        || !tenantIdEquals(account.getTenantId(), tenantId)
        || !Long.valueOf(kolId).equals(account.getKolId())
        || account.getStatus() != PayoutAccountStatus.APPROVED) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return account;
  }

  private static PrepayWrite.Details detailsCommand(
      long id,
      ApplicationDetails details,
      PayoutAccountDO account,
      boolean manual,
      String cipherText,
      long actorId,
      Instant now) {
    return new PrepayWrite.Details(
        id,
        details.reason().strip(),
        details.expectedPayDate(),
        details.deductFromCommission(),
        manual ? null : account.getId(),
        manual
            ? "MANUAL"
            : // Unknown stored type reads back as null instead of NPE (#106)
            account.getAccountType() == null ? null : account.getAccountType().name(),
        manual ? null : account.getCurrency(),
        manual || account.getUsdtNetwork() == null ? null : account.getUsdtNetwork().name(),
        manual ? null : account.getHolderName(),
        cipherText,
        manual ? "***" : account.getAccountMasked(),
        actorId,
        now);
  }

  private PrepayRow requireTaskLocked(long id) {
    PrepayRow row = findRow(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (prepays.lockById(id) == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return row;
  }

  private String status(long id) {
    String value = prepays.statusOf(id);
    if (value == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return value;
  }

  private PrepayApplicationColumns application(long id) {
    PrepayApplicationColumns columns = prepays.loadApplication(id);
    if (columns == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return columns;
  }

  private PrepayRow findByRequest(UUID requestId) {
    return toIdentity(prepays.findByRequestId(requestId));
  }

  private PrepayRow findRow(long id) {
    return toIdentity(prepays.findById(id));
  }

  private PrepayRow toIdentity(PrepayHead head) {
    if (head == null) {
      return null;
    }
    return toRow(head, null, null);
  }

  private PrepayRow toRow(PrepayHead head, KolRefDTO kol, String applicantName) {
    return new PrepayRow(
        head.id(),
        head.prepayNo(),
        "PREPAY",
        head.id(),
        head.kolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        head.applicantId(),
        applicantName,
        money(head.amountCny()),
        "CNY",
        head.status(),
        head.createTime());
  }

  private PayoutOption snapshot(PrepayApplicationColumns values) {
    if (values.payoutAccountType() == null) {
      return null;
    }
    return new PayoutOption(
        values.payoutAccountId(),
        values.payoutAccountType(),
        values.payoutCurrency(),
        values.payoutNetwork(),
        values.payoutAccountMasked(),
        values.payoutHolderName());
  }

  private Approval approval(PrepayHistoryRow row) {
    return new Approval(
        "REJECTED".equals(row.action()) ? "REJECT" : "APPROVE",
        row.operatorId(),
        row.createTime(),
        row.reason(),
        actorName(row),
        actorRole(row));
  }

  private Payment payment(PrepayPaymentRow row) {
    return new Payment(
        money(row.payAmountCny()),
        "CNY",
        row.payTime(),
        row.payMethod(),
        row.payRemark(),
        row.voucherFileId(),
        row.operatorId(),
        actorName(row.operatorId(), row.operatorName()),
        actorRole(row.operatorId(), row.operatorRole()));
  }

  private Timeline timeline(PrepayHistoryRow row) {
    return new Timeline(
        row.action(),
        row.operatorId(),
        row.createTime(),
        row.reason(),
        actorName(row),
        actorRole(row));
  }

  private Deduction deduction(long prepayId, String status, Boolean deduct) {
    if (!"PAID".equals(status) || !Boolean.TRUE.equals(deduct)) {
      return null;
    }
    PrepayDeductionRow row = prepays.findDeduction(prepayId);
    if (row == null) {
      return null;
    }
    BigDecimal recovered = prepays.sumRecovered(row.ledgerId());
    if (recovered == null) {
      recovered = BigDecimal.ZERO;
    }
    return new Deduction(
        money(row.posted()), money(recovered), money(row.posted().subtract(recovered)));
  }

  private KolRefDTO requireKol(long kolId) {
    List<KolRefDTO> rows = kols.listByIds(List.of(kolId));
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return rows.getFirst();
  }

  private String applicantName(Long applicantId) {
    if (applicantId == null) {
      return null;
    }
    return namesById(List.of(applicantId)).get(applicantId);
  }

  private Map<Long, KolRefDTO> kolsById(List<Long> ids) {
    Map<Long, KolRefDTO> byId = new HashMap<>();
    for (KolRefDTO kol : kols.listByIdsIncludingDeleted(ids)) {
      byId.put(kol.id(), kol);
    }
    return byId;
  }

  private Map<Long, String> namesById(List<Long> ids) {
    Map<Long, String> byId = new HashMap<>();
    for (AccountNameDTO name : accountNames.listByIds(ids)) {
      byId.put(name.id(), name.nickname());
    }
    return byId;
  }

  private static void requireKolScope(AccountDTO user, KolRefDTO kol) {
    if (user.ownScope() && !user.id().equals(kol.bdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static long requireTenant(AccountDTO user) {
    if (user == null || user.role() == Role.PLATFORM_ADMIN || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static boolean hasFinanceAccess(AccountDTO user) {
    return user.role() == Role.SUPER_ADMIN
        || user.permissions().contains("prepay:review")
        || user.permissions().contains("prepay:finance-review")
        || user.permissions().contains("prepay:pay");
  }

  private static void requirePermission(AccountDTO user, String code) {
    if (user.role() != Role.SUPER_ADMIN && !user.permissions().contains(code)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static void requireAnyPermission(AccountDTO user, String first, String second) {
    if (user.role() != Role.SUPER_ADMIN
        && !user.permissions().contains(first)
        && !user.permissions().contains(second)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static void requireAnyPermission(
      AccountDTO user, String first, String second, String third) {
    if (user.role() != Role.SUPER_ADMIN
        && !user.permissions().contains(first)
        && !user.permissions().contains(second)
        && !user.permissions().contains(third)) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static void requireDetailPermission(AccountDTO user) {
    if (!hasFinanceAccess(user)
        && !user.permissions().contains("commission:view")
        && !user.permissions().contains("prepay:apply")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static boolean detailsComplete(PrepayDetail detail) {
    return detail.reason() != null
        && !detail.reason().isBlank()
        && detail.expectedPayDate() != null
        && detail.deductFromCommission() != null
        && detail.payoutAccountSnapshot() != null;
  }

  private static BigDecimal money(String value) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    if (amount.signum() <= 0 || amount.compareTo(MAX_MONEY) > 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return amount;
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static UUID parseUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) {
        throw new IllegalArgumentException("A canonical UUID is required");
      }
      return parsed;
    } catch (RuntimeException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static Instant payTime(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MILLIS);
    } catch (RuntimeException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static String paymentFingerprint(
      long id,
      UUID requestId,
      BigDecimal amount,
      Instant payTime,
      String method,
      String remark,
      byte[] voucher) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(
          (id
                  + "|"
                  + requestId
                  + "|"
                  + amount.toPlainString()
                  + "|"
                  + payTime
                  + "|"
                  + method
                  + "|"
                  + (remark == null ? "" : remark)
                  + "|")
              .getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest.digest(voucher));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private boolean paymentRecorded(UUID requestId, String fingerprint) {
    String prior = prepays.findFingerprint(requestId);
    if (prior == null) {
      return false;
    }
    if (!fingerprint.equals(prior)) {
      throw new BusinessException(ErrorCode.PREPAY_PAYMENT_CONFLICT);
    }
    return true;
  }

  private static long requirePositive(Long value) {
    if (value == null || value <= 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  private static boolean tenantIdEquals(Long id, long tenantId) {
    return id != null && id == tenantId;
  }

  private static String stripToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }

  private String nextPrepayNo(long id, Instant now) {
    LocalDate date = now.atZone(NUMBER_ZONE).toLocalDate();
    Integer sequence = prepays.nextSequence(id, date);
    if (sequence == null) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    return "PP" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("%06d", sequence);
  }

  private void insertHistory(
      long id, String action, AccountDTO actor, String actorRole, String reason, Instant now) {
    prepays.insertHistory(
        new PrepayWrite.History(
            IdWorker.getId(), id, action, actor.id(), reason, now, actor.nickname(), actorRole));
  }

  private static String actorName(PrepayHistoryRow row) {
    return actorName(row.operatorId(), row.operatorName());
  }

  private static String actorRole(PrepayHistoryRow row) {
    return actorRole(row.operatorId(), row.operatorRole());
  }

  private static String actorName(Long operatorId, String operatorName) {
    if (operatorId == null || operatorId == 0L) {
      return null;
    }
    return operatorName;
  }

  private static String actorRole(Long operatorId, String operatorRole) {
    if (operatorId == null || operatorId == 0L) {
      return "SYSTEM";
    }
    return operatorRole == null ? "HISTORICAL" : operatorRole;
  }

  private void insertOutbox(long id, String type, String prepayNo, Instant now) {
    String escaped = prepayNo.replace("\\", "\\\\").replace("\"", "\\\"");
    prepays.insertOutbox(
        new PrepayWrite.Outbox(
            IdWorker.getId(), id, type, "{\"prepayNo\":\"" + escaped + "\"}", now, now));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
