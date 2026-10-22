package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepay creation and finance completion rules. All SQL includes the current company explicitly.
 */
@Service
public class PrepayService {
  private static final ZoneId NUMBER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private final JdbcTemplate jdbc;
  private final KolApi kols;
  private final PayoutAccountMapper payoutAccounts;
  private final BizFileService files;
  private final SecretCipher cipher;
  private final CommissionWalletService wallet;
  private final ExternalAuditApi audit;
  private final Clock clock;

  @Autowired
  public PrepayService(
      JdbcTemplate jdbc,
      KolApi kols,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      CommissionWalletService wallet,
      ExternalAuditApi audit) {
    this(jdbc, kols, payoutAccounts, files, cipher, wallet, audit, Clock.systemUTC());
  }

  PrepayService(
      JdbcTemplate jdbc,
      KolApi kols,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      CommissionWalletService wallet,
      ExternalAuditApi audit,
      Clock clock) {
    this.jdbc = jdbc;
    this.kols = kols;
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
      String amountCny,
      String currency,
      String status,
      Instant createdAt) {}

  public record PrepayApplication(String requestId, Long kolId, String amountCny) {}

  public record ApplicationDetails(
      String reason,
      LocalDate expectedPayDate,
      Long payoutAccountId,
      Boolean deductFromCommission) {}

  public record UploadedFile(String fileName, byte[] content) {}

  public record PaymentCommand(
      String requestId, String payAmountCny, String payTime, String payMethod, String payRemark) {}

  public record Filter(String prepayNo, String kolCode, String status, int page, int size) {}

  public record Approval(String decision, Long operatorId, Instant time, String reason) {}

  public record Payment(
      String payAmountCny,
      String payCurrency,
      Instant payTime,
      String payMethod,
      String payRemark,
      Long voucherFileId,
      Long operatorId) {}

  public record Timeline(String action, Long operatorId, Instant time, String reason) {}

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
      String walletBalanceCny) {}

  public record Recipient(
      String type, String currency, String network, String holderName, String account) {}

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail apply(AccountDTO user, PrepayApplication request) {
    requirePermission(user, "prepay:apply");
    long tenantId = requireTenant(user);
    UUID requestId = parseUuid(request.requestId());
    long kolId = requirePositive(request.kolId());
    BigDecimal amount = money(request.amountCny());
    var existing = findByRequest(tenantId, requestId);
    if (existing != null) {
      return returnExistingRequest(user, existing, kolId, amount);
    }
    if (!kols.lockKol(kolId)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    existing = findByRequest(tenantId, requestId);
    if (existing != null) {
      return returnExistingRequest(user, existing, kolId, amount);
    }
    KolRefDTO kol = requireKol(kolId);
    requireKolScope(user, kol);
    Instant now = now();
    long id = IdWorker.getId();
    String prepayNo = nextPrepayNo(tenantId, id, now);
    int created =
        jdbc.update(
            "INSERT INTO crm_prepay (id, tenant_id, kol_id, prepay_no, request_id, applicant_id, amount_cny, status, create_by, create_time, update_by, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, ?) ON CONFLICT (tenant_id, request_id) DO NOTHING",
            id,
            tenantId,
            kolId,
            prepayNo,
            requestId,
            user.id(),
            amount,
            user.id(),
            Timestamp.from(now),
            user.id(),
            Timestamp.from(now));
    if (created == 0) {
      PrepayRow duplicate = findByRequest(tenantId, requestId);
      if (duplicate == null) {
        throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
      }
      return returnExistingRequest(user, duplicate, kolId, amount);
    }
    insertHistory(tenantId, id, "SUBMITTED", user.id(), null, now);
    insertOutbox(tenantId, id, "SUBMITTED", prepayNo, now);
    audit.recordTenantWriteOperation(
        tenantId,
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

  /** Saves finance-supplied fields while the task is pending; null deduction is never inferred. */
  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail saveApplicationDetails(
      AccountDTO user, long id, ApplicationDetails details, List<UploadedFile> attachments) {
    requireAnyPermission(user, "prepay:review", "prepay:pay");
    long tenantId = requireTenant(user);
    validateApplicationDetails(details);
    PrepayRow row = requireTaskLocked(tenantId, id);
    if (!"PENDING_APPROVAL".equals(status(tenantId, id))) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    KolRefDTO kol = requireKol(row.kolId());
    PayoutAccountDO account = payoutAccounts.selectById(details.payoutAccountId());
    if (account == null
        || !tenantIdEquals(account.getTenantId(), tenantId)
        || !row.kolId().equals(account.getKolId())
        || account.getStatus() != PayoutAccountStatus.APPROVED) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    Instant now = now();
    List<BizFileService.FileMeta> existingFiles = files.prepayAttachments(id);
    List<UploadedFile> additions = attachments == null ? List.of() : List.copyOf(attachments);
    if (existingFiles.size() + additions.size() > 5) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    for (UploadedFile attachment : additions) {
      files.uploadPrepayAdmin(
          tenantId,
          user.id(),
          id,
          FileBizType.PREPAY_ATTACHMENT,
          attachment.fileName(),
          attachment.content());
    }
    jdbc.update(
        "UPDATE crm_prepay SET reason = ?, expected_pay_date = ?, deduct_from_commission = ?, payout_account_id = ?, payout_account_type = ?, payout_currency = ?, payout_network = ?, payout_holder_name = ?, payout_account_cipher = ?, payout_account_masked = ?, update_by = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'",
        details.reason().strip(),
        Date.valueOf(details.expectedPayDate()),
        details.deductFromCommission(),
        account.getId(),
        account.getAccountType().name(),
        account.getCurrency(),
        account.getUsdtNetwork() == null ? null : account.getUsdtNetwork().name(),
        account.getHolderName(),
        account.getAccountCipher(),
        account.getAccountMasked(),
        user.id(),
        Timestamp.from(now),
        tenantId,
        id);
    insertHistory(
        tenantId, id, "DETAILS_COMPLETED", user.id(), "Finance application details saved", now);
    audit.recordTenantWriteOperation(
        tenantId,
        user.id(),
        "Prepay",
        "Complete application details",
        "PUT",
        "/admin-api/commission/prepays/" + id + "/application-details",
        "{\"prepayId\":\"" + id + "\",\"kolId\":\"" + kol.id() + "\"}");
    return detail(user, id);
  }

  public List<PayoutOption> payoutOptions(AccountDTO user, long kolId) {
    requireAnyPermission(user, "prepay:apply", "prepay:review", "prepay:pay");
    requireTenant(user);
    KolRefDTO kol = requireKol(kolId);
    if (!hasAny(user, "prepay:review", "prepay:pay")) {
      requireKolScope(user, kol);
    }
    return payoutAccounts
        .selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayoutAccountDO>()
                .eq(PayoutAccountDO::getKolId, kolId)
                .eq(PayoutAccountDO::getStatus, PayoutAccountStatus.APPROVED)
                .orderByAsc(PayoutAccountDO::getId))
        .stream()
        .map(
            account ->
                new PayoutOption(
                    account.getId(),
                    account.getAccountType().name(),
                    account.getCurrency(),
                    account.getUsdtNetwork() == null ? null : account.getUsdtNetwork().name(),
                    account.getAccountMasked(),
                    account.getHolderName()))
        .toList();
  }

  @Transactional(rollbackFor = Exception.class)
  public PrepayDetail approval(AccountDTO user, long id, String decision, String reason) {
    requirePermission(user, "prepay:review");
    long tenantId = requireTenant(user);
    requireTaskLocked(tenantId, id);
    PrepayDetail detail = detail(user, id);
    if (!"PENDING_APPROVAL".equals(detail.status())) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
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
    String next = approve ? "APPROVED" : "REJECTED";
    int changed =
        jdbc.update(
            "UPDATE crm_prepay SET status = ?, approved_by = ?, approved_time = ?, approval_reason = ?, update_by = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'",
            next,
            approve ? user.id() : null,
            approve ? Timestamp.from(now) : null,
            approve ? null : normalizedReason,
            user.id(),
            Timestamp.from(now),
            tenantId,
            id);
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    String action = approve ? "APPROVED" : "REJECTED";
    insertHistory(tenantId, id, action, user.id(), normalizedReason, now);
    insertOutbox(tenantId, id, action, detail.prepayNo(), now);
    audit.recordTenantWriteOperation(
        tenantId,
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
    requireTaskLocked(tenantId, id);
    PrepayDetail detail = detail(user, id);
    if (!"PENDING_APPROVAL".equals(detail.status()) || !detail.applicantId().equals(user.id())) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    KolRefDTO kol = requireKol(detail.kolId());
    requireKolScope(user, kol);
    Instant now = now();
    String normalizedReason = reason == null || reason.isBlank() ? null : reason.strip();
    if (normalizedReason != null && normalizedReason.length() > 1000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    int changed =
        jdbc.update(
            "UPDATE crm_prepay SET status = 'CANCELLED', cancelled_by = ?, cancelled_time = ?, cancel_reason = ?, update_by = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = 'PENDING_APPROVAL'",
            user.id(),
            Timestamp.from(now),
            normalizedReason,
            user.id(),
            Timestamp.from(now),
            tenantId,
            id);
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    insertHistory(tenantId, id, "CANCELLED", user.id(), normalizedReason, now);
    audit.recordTenantWriteOperation(
        tenantId,
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
    Instant payTime;
    try {
      payTime = OffsetDateTime.parse(request.payTime()).toInstant().truncatedTo(ChronoUnit.MILLIS);
    } catch (RuntimeException exception) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
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
    if (paymentRecorded(tenantId, requestId, fingerprint)) {
      return detail(user, id);
    }
    PrepayRow row = requireTaskLocked(tenantId, id);
    // A duplicate may commit while this request waits for the task lock.
    if (paymentRecorded(tenantId, requestId, fingerprint)) {
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
            tenantId,
            user.id(),
            id,
            FileBizType.PREPAY_VOUCHER,
            voucher.fileName(),
            voucher.content());
    Instant now = now();
    jdbc.update(
        "INSERT INTO crm_prepay_payment (id, tenant_id, prepay_id, request_id, request_fingerprint, pay_amount_cny, pay_time, pay_method, pay_remark, voucher_file_id, operator_id, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        IdWorker.getId(),
        tenantId,
        id,
        requestId,
        fingerprint,
        amount,
        Timestamp.from(payTime),
        method,
        remark,
        voucherId,
        user.id(),
        Timestamp.from(now));
    int changed =
        jdbc.update(
            "UPDATE crm_prepay SET status = 'PAID', update_by = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = 'APPROVED'",
            user.id(),
            Timestamp.from(now),
            tenantId,
            id);
    if (changed != 1) {
      throw new BusinessException(ErrorCode.PREPAY_STATE_CONFLICT);
    }
    if (Boolean.TRUE.equals(task.deductFromCommission())) {
      wallet.postPrepayDeduction(tenantId, row.kolId(), id, amount, payTime, user.id());
    }
    insertHistory(tenantId, id, "PAID", user.id(), null, now);
    insertOutbox(tenantId, id, "PAID", row.prepayNo(), now);
    audit.recordTenantWriteOperation(
        tenantId,
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
    requirePermission(user, "prepay:pay");
    long tenantId = requireTenant(user);
    try {
      jdbc.queryForObject(
          "SELECT id FROM crm_prepay WHERE tenant_id = ? AND id = ? FOR UPDATE",
          Long.class,
          tenantId,
          id);
      return jdbc.queryForObject(
          "SELECT payout_account_type, payout_currency, payout_network, payout_holder_name, payout_account_cipher, prepay_no FROM crm_prepay WHERE tenant_id = ? AND id = ? AND payout_account_cipher IS NOT NULL",
          (rs, index) -> {
            audit.recordTenantOperation(
                tenantId,
                user.id(),
                "Prepay",
                "View recipient",
                "/admin-api/commission/prepays/" + id + "/recipient",
                "{\"prepayNo\":\"" + rs.getString("prepay_no") + "\"}");
            return new Recipient(
                rs.getString("payout_account_type"),
                rs.getString("payout_currency"),
                rs.getString("payout_network"),
                rs.getString("payout_holder_name"),
                cipher.decrypt(rs.getString("payout_account_cipher")));
          },
          tenantId,
          id);
    } catch (EmptyResultDataAccessException exception) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
  }

  public BizFileService.FileDTO adminFile(AccountDTO user, long fileId) {
    requireAnyPermission(user, "prepay:review", "prepay:pay", "commission:view");
    long tenantId = requireTenant(user);
    BizFileService.AttachedFile file = files.attachedFile(fileId);
    if ((file.bizType() != FileBizType.PREPAY_ATTACHMENT
            && file.bizType() != FileBizType.PREPAY_VOUCHER)
        || file.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    PrepayRow row = findRow(tenantId, file.bizId());
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (!hasAny(user, "prepay:review", "prepay:pay")) {
      requireKolScope(user, requireKol(row.kolId()));
    }
    return file.download();
  }

  public BizFileService.FileDTO kolFile(long kolId, long fileId) {
    BizFileService.AttachedFile file = files.attachedFile(fileId);
    if (file.bizType() != FileBizType.PREPAY_VOUCHER || file.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    Long prepayKol =
        jdbc.query(
            "SELECT p.kol_id FROM crm_prepay p JOIN crm_prepay_payment payment ON payment.tenant_id = p.tenant_id AND payment.prepay_id = p.id WHERE p.tenant_id = ? AND p.id = ? AND payment.voucher_file_id = ? AND p.status = 'PAID'",
            rs -> rs.next() ? rs.getLong(1) : null,
            TenantScope.companyId(),
            file.bizId(),
            fileId);
    if (prepayKol == null || prepayKol != kolId) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return file.download();
  }

  private static boolean detailsComplete(PrepayDetail detail) {
    return detail.reason() != null
        && !detail.reason().isBlank()
        && detail.expectedPayDate() != null
        && detail.deductFromCommission() != null
        && detail.payoutAccountSnapshot() != null;
  }

  public PrepayDetail detail(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    PrepayRow row = findRow(tenantId, id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolRefDTO kol = requireKol(row.kolId());
    if (hasAny(user, "prepay:review", "prepay:pay")) {
      // Finance permissions deliberately see every task in the current company.
    } else {
      requireKolScope(user, kol);
    }
    AppValues values =
        jdbc.queryForObject(
            "SELECT reason, expected_pay_date, deduct_from_commission, payout_account_id, payout_account_type, payout_currency, payout_network, payout_holder_name, payout_account_masked FROM crm_prepay WHERE tenant_id = ? AND id = ?",
            (rs, index) ->
                new AppValues(
                    rs.getString("reason"),
                    localDate(rs, "expected_pay_date"),
                    (Boolean) rs.getObject("deduct_from_commission"),
                    rs.getObject("payout_account_id", Long.class),
                    rs.getString("payout_account_type") == null
                        ? null
                        : new PayoutOption(
                            rs.getObject("payout_account_id", Long.class),
                            rs.getString("payout_account_type"),
                            rs.getString("payout_currency"),
                            rs.getString("payout_network"),
                            rs.getString("payout_account_masked"),
                            rs.getString("payout_holder_name")),
                    files.prepayAttachments(id)),
            tenantId,
            id);
    List<Approval> approvals =
        jdbc.query(
            "SELECT action, operator_id, create_time, reason FROM crm_prepay_history WHERE tenant_id = ? AND prepay_id = ? AND action IN ('APPROVED', 'REJECTED') ORDER BY create_time DESC, id DESC LIMIT 1",
            (rs, index) ->
                new Approval(
                    "APPROVED".equals(rs.getString("action")) ? "APPROVE" : "REJECT",
                    rs.getLong("operator_id"),
                    rs.getTimestamp("create_time").toInstant(),
                    rs.getString("reason")),
            tenantId,
            id);
    List<Payment> payments =
        jdbc.query(
            "SELECT pay_amount_cny, pay_time, pay_method, pay_remark, voucher_file_id, operator_id FROM crm_prepay_payment WHERE tenant_id = ? AND prepay_id = ?",
            (rs, index) ->
                new Payment(
                    money(rs.getBigDecimal("pay_amount_cny")),
                    "CNY",
                    rs.getTimestamp("pay_time").toInstant(),
                    rs.getString("pay_method"),
                    rs.getString("pay_remark"),
                    rs.getLong("voucher_file_id"),
                    rs.getLong("operator_id")),
            tenantId,
            id);
    List<Timeline> timeline =
        jdbc.query(
            "SELECT action, operator_id, create_time, reason FROM crm_prepay_history WHERE tenant_id = ? AND prepay_id = ? ORDER BY create_time, id",
            (rs, index) ->
                new Timeline(
                    rs.getString("action"),
                    rs.getLong("operator_id"),
                    rs.getTimestamp("create_time").toInstant(),
                    rs.getString("reason")),
            tenantId,
            id);
    Deduction deduction = deduction(tenantId, id, row.status(), values.deductFromCommission());
    return new PrepayDetail(
        row.id(),
        row.prepayNo(),
        row.financeTaskType(),
        row.financeTaskId(),
        row.kolId(),
        row.kolCode(),
        row.kolName(),
        row.applicantId(),
        row.amountCny(),
        row.currency(),
        row.status(),
        row.createdAt(),
        values.reason(),
        values.expectedPayDate(),
        values.deductFromCommission(),
        values.payoutAccountId(),
        values.payoutAccountSnapshot(),
        values.attachments(),
        approvals.isEmpty() ? null : approvals.getFirst(),
        payments.isEmpty() ? null : payments.getFirst(),
        timeline,
        deduction,
        money(wallet.currentBalance(tenantId, row.kolId())));
  }

  private Deduction deduction(long tenantId, long prepayId, String status, Boolean deduct) {
    if (!"PAID".equals(status) || !Boolean.TRUE.equals(deduct)) {
      return null;
    }
    List<Deduction> result =
        jdbc.query(
            "SELECT p.pay_amount_cny AS posted, l.id AS ledger_id FROM crm_prepay_payment p JOIN crm_commission_ledger l ON l.tenant_id = p.tenant_id AND l.source_id = p.prepay_id AND l.source_type = 'PREPAY' AND l.entry_type = 'PREPAY_DEDUCT' WHERE p.tenant_id = ? AND p.prepay_id = ?",
            (rs, index) -> {
              BigDecimal posted = rs.getBigDecimal("posted");
              BigDecimal recovered =
                  jdbc.queryForObject(
                      "SELECT COALESCE(SUM(amount_cny), 0) FROM crm_commission_recovery WHERE tenant_id = ? AND deduction_ledger_id = ?",
                      BigDecimal.class,
                      tenantId,
                      rs.getLong("ledger_id"));
              return new Deduction(
                  money(posted), money(recovered), money(posted.subtract(recovered)));
            },
            tenantId,
            prepayId);
    return result.isEmpty() ? null : result.getFirst();
  }

  public PageResult<PrepayRow> page(AccountDTO user, Filter filter) {
    long tenantId = requireTenant(user);
    if (!hasAny(user, "prepay:review", "prepay:pay")
        && !user.permissions().contains("commission:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (filter.page() < 1 || filter.size() < 1 || filter.size() > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE p.tenant_id = ?");
    args.add(tenantId);
    if (filter.prepayNo() != null && !filter.prepayNo().isBlank()) {
      where.append(" AND p.prepay_no = ?");
      args.add(filter.prepayNo().strip());
    }
    if (filter.kolCode() != null && !filter.kolCode().isBlank()) {
      where.append(" AND k.kol_code = ?");
      args.add(filter.kolCode().strip());
    }
    if (filter.status() != null && !filter.status().isBlank()) {
      if (!List.of("PENDING_APPROVAL", "APPROVED", "PAID", "REJECTED", "CANCELLED")
          .contains(filter.status())) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      where.append(" AND p.status = ?");
      args.add(filter.status());
    }
    if (!hasAny(user, "prepay:review", "prepay:pay") && user.ownScope()) {
      List<Long> visible =
          kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).distinct().toList();
      if (visible.isEmpty()) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
      where.append(" AND p.kol_id IN (");
      where.append(String.join(",", java.util.Collections.nCopies(visible.size(), "?")));
      where.append(')');
      args.addAll(visible);
    }
    String from =
        " FROM crm_prepay p JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = p.tenant_id";
    Long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());
    args.add(filter.size());
    args.add((long) (filter.page() - 1) * filter.size());
    List<PrepayRow> rows =
        jdbc.query(
            "SELECT p.id, p.prepay_no, p.kol_id, k.kol_code, k.name AS kol_name, p.applicant_id, p.amount_cny, p.status, p.create_time"
                + from
                + where
                + " ORDER BY p.create_time DESC, p.id DESC LIMIT ? OFFSET ?",
            this::mapRow,
            args.toArray());
    return new PageResult<>(rows, total == null ? 0L : total, filter.page(), filter.size());
  }

  @Transactional(readOnly = true)
  public byte[] export(AccountDTO user, Filter filter) {
    requireTenant(user);
    if (user.role() != Role.SUPER_ADMIN
        && (!user.permissions().contains("data:export")
            || !hasAny(user, "prepay:review", "prepay:pay")
                && !user.permissions().contains("commission:view"))) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    StringBuilder csv =
        new StringBuilder("\uFEFF预付编号,达人编号,达人名称,申请人ID,申请金额(CNY),状态,付款金额(CNY),付款时间,付款方式,脱敏收款账户\r\n");
    PageResult<PrepayRow> first =
        page(user, new Filter(filter.prepayNo(), filter.kolCode(), filter.status(), 1, 100));
    if (first.total() > 100000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    appendExportRows(csv, user, first.items());
    for (int page = 2; (long) (page - 1) * 100 < first.total(); page++) {
      PageResult<PrepayRow> next =
          page(user, new Filter(filter.prepayNo(), filter.kolCode(), filter.status(), page, 100));
      appendExportRows(csv, user, next.items());
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

  private void appendExportRows(StringBuilder csv, AccountDTO user, List<PrepayRow> rows) {
    for (PrepayRow row : rows) {
      List<Payment> payments =
          jdbc.query(
              "SELECT pay_amount_cny, pay_time, pay_method FROM crm_prepay_payment WHERE tenant_id = ? AND prepay_id = ?",
              (rs, index) ->
                  new Payment(
                      money(rs.getBigDecimal("pay_amount_cny")),
                      "CNY",
                      rs.getTimestamp("pay_time").toInstant(),
                      rs.getString("pay_method"),
                      null,
                      null,
                      null),
              user.tenantId(),
              row.id());
      String[] fields = {
        row.prepayNo(),
        row.kolCode(),
        row.kolName(),
        row.applicantId().toString(),
        row.amountCny(),
        row.status(),
        payments.isEmpty() ? "" : payments.getFirst().payAmountCny(),
        payments.isEmpty() ? "" : payments.getFirst().payTime().toString(),
        payments.isEmpty() ? "" : payments.getFirst().payMethod(),
        maskedAccount(user.tenantId(), row.id())
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

  private String maskedAccount(long tenantId, long id) {
    List<String> rows =
        jdbc.query(
            "SELECT payout_account_masked FROM crm_prepay WHERE tenant_id = ? AND id = ?",
            (rs, index) -> rs.getString(1),
            tenantId,
            id);
    return rows.isEmpty() ? "" : rows.getFirst();
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
        || details.payoutAccountId() == null
        || details.deductFromCommission() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private PrepayRow requireTaskLocked(long tenantId, long id) {
    PrepayRow row = findRow(tenantId, id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    jdbc.queryForObject(
        "SELECT id FROM crm_prepay WHERE tenant_id = ? AND id = ? FOR UPDATE",
        Long.class,
        tenantId,
        id);
    return row;
  }

  private String status(long tenantId, long id) {
    return jdbc.queryForObject(
        "SELECT status FROM crm_prepay WHERE tenant_id = ? AND id = ?", String.class, tenantId, id);
  }

  private PrepayRow findByRequest(long tenantId, UUID requestId) {
    List<PrepayRow> rows =
        jdbc.query(
            "SELECT p.id, p.prepay_no, p.kol_id, k.kol_code, k.name AS kol_name, p.applicant_id, p.amount_cny, p.status, p.create_time FROM crm_prepay p JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = p.tenant_id WHERE p.tenant_id = ? AND p.request_id = ?",
            this::mapRow,
            tenantId,
            requestId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private PrepayRow findRow(long tenantId, long id) {
    List<PrepayRow> rows =
        jdbc.query(
            "SELECT p.id, p.prepay_no, p.kol_id, k.kol_code, k.name AS kol_name, p.applicant_id, p.amount_cny, p.status, p.create_time FROM crm_prepay p JOIN crm_kol k ON k.id = p.kol_id AND k.tenant_id = p.tenant_id WHERE p.tenant_id = ? AND p.id = ?",
            this::mapRow,
            tenantId,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private PrepayRow mapRow(ResultSet rs, int index) throws SQLException {
    return new PrepayRow(
        rs.getLong("id"),
        rs.getString("prepay_no"),
        "PREPAY",
        rs.getLong("id"),
        rs.getLong("kol_id"),
        rs.getString("kol_code"),
        rs.getString("kol_name"),
        rs.getLong("applicant_id"),
        rs.getBigDecimal("amount_cny").setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
        "CNY",
        rs.getString("status"),
        rs.getTimestamp("create_time").toInstant());
  }

  private KolRefDTO requireKol(long kolId) {
    List<KolRefDTO> rows = kols.listByIds(List.of(kolId));
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return rows.getFirst();
  }

  private static void requireKolScope(AccountDTO user, KolRefDTO kol) {
    if (user.ownScope() && !user.id().equals(kol.bdUserId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
  }

  private static long requireTenant(AccountDTO user) {
    if (user == null || user.role() == Role.PLATFORM_ADMIN || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static boolean hasAny(AccountDTO user, String first, String second) {
    return user.role() == Role.SUPER_ADMIN
        || user.permissions().contains(first)
        || user.permissions().contains(second);
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

  private boolean paymentRecorded(long tenantId, UUID requestId, String fingerprint) {
    List<String> priorFingerprints =
        jdbc.query(
            "SELECT request_fingerprint FROM crm_prepay_payment WHERE tenant_id = ? AND request_id = ?",
            (rs, index) -> rs.getString(1),
            tenantId,
            requestId);
    if (priorFingerprints.isEmpty()) {
      return false;
    }
    if (!fingerprint.equals(priorFingerprints.getFirst())) {
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

  private String nextPrepayNo(long tenantId, long id, Instant now) {
    LocalDate date = now.atZone(NUMBER_ZONE).toLocalDate();
    Integer sequence =
        jdbc.queryForObject(
            "INSERT INTO crm_prepay_sequence (id, tenant_id, sequence_date, last_value) VALUES (?, ?, ?, 1) ON CONFLICT (tenant_id, sequence_date) DO UPDATE SET last_value = crm_prepay_sequence.last_value + 1 WHERE crm_prepay_sequence.last_value < 999999 RETURNING last_value",
            Integer.class,
            id,
            tenantId,
            Date.valueOf(date));
    if (sequence == null) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    return "PP"
        + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        + String.format("%06d", sequence);
  }

  private void insertHistory(
      long tenantId, long id, String action, long actor, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO crm_prepay_history (id, tenant_id, prepay_id, action, operator_id, reason, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
        IdWorker.getId(),
        tenantId,
        id,
        action,
        actor,
        reason,
        Timestamp.from(now));
  }

  private void insertOutbox(long tenantId, long id, String type, String prepayNo, Instant now) {
    String escaped = prepayNo.replace("\\", "\\\\").replace("\"", "\\\"");
    jdbc.update(
        "INSERT INTO crm_prepay_outbox (id, tenant_id, prepay_id, event_type, payload, attempts, next_attempt_at, create_time) VALUES (?, ?, ?, ?, CAST(? AS jsonb), 0, ?, ?)",
        IdWorker.getId(),
        tenantId,
        id,
        type,
        "{\"prepayNo\":\"" + escaped + "\"}",
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
    Date date = rs.getDate(column);
    return date == null ? null : date.toLocalDate();
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private record AppValues(
      String reason,
      LocalDate expectedPayDate,
      Boolean deductFromCommission,
      Long payoutAccountId,
      PayoutOption payoutAccountSnapshot,
      List<BizFileService.FileMeta> attachments) {}
}
