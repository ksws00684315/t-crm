package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.mapper.WithdrawalFreezeMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.FinanceTaskQuery;
import com.tcrm.admin.commission.model.FinanceTaskRow;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.model.WithdrawalPageQuery;
import com.tcrm.admin.commission.model.WithdrawalRow;
import com.tcrm.admin.commission.model.WithdrawalRows;
import com.tcrm.admin.commission.model.WithdrawalWrite;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderItemInfoApi;
import com.tcrm.admin.order.api.OrderItemInfoApi.OrderItemInfoDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commission withdrawal workflow (task 63, rows 12.01/12.02/13.01-13.04/07.05): KOL applications
 * with per-application payout snapshots, FIFO allocation over released order items, one review
 * step, payment registration that leaves the shared wallet, creator freeze, the unified finance
 * task list and CSV export. Company isolation is the tenant plugin's job.
 */
@Service
public class WithdrawalService {
  private static final ZoneId NUMBER_ZONE = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final List<String> STATUSES =
      List.of("PENDING_REVIEW", "PENDING_PAYMENT", "REJECTED", "WITHDRAWN", "PAID");
  private static final List<String> TASK_STATUSES =
      List.of("PENDING_REVIEW", "PENDING_PAYMENT", "PAID", "REJECTED", "CANCELLED");
  private static final List<String> PREPAY_STATUSES =
      List.of(
          "WAITING_DETAILS",
          "PENDING_OPERATIONS_REVIEW",
          "PENDING_FINANCE_REVIEW",
          "APPROVED",
          "PAID",
          "REJECTED",
          "CANCELLED");
  private static final int MAX_VOUCHER_BYTES = 2 * 1024 * 1024;
  private static final java.util.regex.Pattern EMAIL =
      java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final String MINIMUM_KEY = "commission.minimum-withdrawal-cny";

  private final WithdrawalMapper withdrawals;
  private final WithdrawalFreezeMapper freezes;
  private final CommissionWalletMapper wallets;
  private final CommissionWalletService wallet;
  private final WithdrawalFundingSource funding;
  private final KolApi kols;
  private final AccountNameApi accountNames;
  private final PayoutAccountMapper payoutAccounts;
  private final BizFileService files;
  private final OrderItemInfoApi orderItems;
  private final ProductAccessApi products;
  private final SecretCipher cipher;
  private final ExternalAuditApi audit;
  private final SystemConfigApi configs;
  private final Clock clock;

  @Autowired
  public WithdrawalService(
      WithdrawalMapper withdrawals,
      WithdrawalFreezeMapper freezes,
      CommissionWalletMapper wallets,
      CommissionWalletService wallet,
      WithdrawalFundingSource funding,
      KolApi kols,
      AccountNameApi accountNames,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      OrderItemInfoApi orderItems,
      ProductAccessApi products,
      SecretCipher cipher,
      ExternalAuditApi audit,
      SystemConfigApi configs) {
    this(
        withdrawals,
        freezes,
        wallets,
        wallet,
        funding,
        kols,
        accountNames,
        payoutAccounts,
        files,
        orderItems,
        products,
        cipher,
        audit,
        configs,
        Clock.systemUTC());
  }

  WithdrawalService(
      WithdrawalMapper withdrawals,
      WithdrawalFreezeMapper freezes,
      CommissionWalletMapper wallets,
      CommissionWalletService wallet,
      WithdrawalFundingSource funding,
      KolApi kols,
      AccountNameApi accountNames,
      PayoutAccountMapper payoutAccounts,
      BizFileService files,
      OrderItemInfoApi orderItems,
      ProductAccessApi products,
      SecretCipher cipher,
      ExternalAuditApi audit,
      SystemConfigApi configs,
      Clock clock) {
    this.withdrawals = withdrawals;
    this.freezes = freezes;
    this.wallets = wallets;
    this.wallet = wallet;
    this.funding = funding;
    this.kols = kols;
    this.accountNames = accountNames;
    this.payoutAccounts = payoutAccounts;
    this.files = files;
    this.orderItems = orderItems;
    this.products = products;
    this.cipher = cipher;
    this.audit = audit;
    this.configs = configs;
    this.clock = clock;
  }

  /** Withdrawal conditions for the KOL portal (11.03): threshold, occupation, frozen money. */
  public record Conditions(
      String currency,
      String minimumWithdrawalCny,
      String frozenCny,
      String occupiedCny,
      String walletBalanceCny,
      String availableCny,
      boolean kolFrozen,
      boolean eligible) {}

  /** Payout snapshot of one application; masked unless the viewer may see the full account. */
  public record AccountView(
      Long accountId,
      String type,
      String account,
      String holder,
      String currency,
      String network,
      Long qrFileId) {}

  /** Inline payout input of one application (12.01: 填写每次随申请保存的收款方式与账户). */
  public record AccountInput(
      String type, String account, String holder, String currency, String network, Long qrFileId) {}

  public record ApplyCommand(
      String idempotencyKey,
      String amountCny,
      Long previousId,
      Long payoutAccountId,
      AccountInput account,
      String note) {}

  public record PaymentCommand(
      String requestId,
      String amountCny,
      String actualAmount,
      String actualCurrency,
      String paidAt,
      String method,
      String financeNote,
      String conversionNote) {}

  public record UploadedFile(String fileName, byte[] content) {}

  public record HistoryItem(
      String action, Long actorId, String actorName, String actorRole, String note, Instant at) {}

  public record PaymentView(
      String amountCny,
      String actualAmount,
      String actualCurrency,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      Long voucherFileId,
      String operatorName,
      String operatorRole) {}

  public record Detail(
      long id,
      String applicationNo,
      long kolId,
      String kolCode,
      String kolName,
      Long bdId,
      String bdName,
      String type,
      String amountCny,
      String currency,
      String status,
      Instant createdAt,
      String note,
      String reviewNote,
      String financeNote,
      Long previousId,
      long allocationCount,
      AccountView account,
      List<HistoryItem> history,
      PaymentView payment) {}

  /** Admin list row (13.02): masked account only, review note hidden from list pages. */
  public record AdminRow(
      long id,
      String applicationNo,
      long kolId,
      String kolCode,
      String kolName,
      Long bdId,
      String bdName,
      String type,
      String amountCny,
      String currency,
      String status,
      Instant createdAt,
      String accountType,
      String accountMasked) {}

  /** KOL list row: the owner sees their own full account snapshot. */
  public record KolRow(
      long id,
      String applicationNo,
      String amountCny,
      String currency,
      String status,
      Instant createdAt,
      String rejectReason,
      Instant paidAt,
      String actualAmount,
      String actualCurrency,
      AccountView account) {}

  public record Recipient(
      String type, String account, String holder, String currency, String network) {}

  public record Filter(String applicationNo, String kolCode, String status, int page, int size) {}

  public record FinanceFilter(
      String applicationNo, String kolCode, String type, String status, int page, int size) {}

  /** One row of the unified finance task list enriched with creator and BD display names. */
  public record FinanceTask(
      long id,
      String taskNo,
      long kolId,
      String kolCode,
      String kolName,
      Long bdId,
      String bdName,
      String taskType,
      String sourceStatus,
      String status,
      String amountCny,
      String currency,
      Instant createdAt) {}

  /** One order-item occupation shown by the order chain (09.04). */
  public record Occupation(
      long orderItemId,
      long withdrawalId,
      String applicationNo,
      String status,
      String occupiedCny,
      String sourceReleasedCny,
      String remainingSnapshotCny,
      Instant submittedAt,
      Instant paidAt) {}

  // ------------------------------------------------------------------ KOL portal

  /** Conditions as seen by the signed-in creator; the portal identity comes from the caller. */
  @Transactional(readOnly = true)
  public Conditions conditions(long tenantId, long kolId) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          BigDecimal balance = nz(wallets.findBalance(kolId));
          BigDecimal occupied = nz(withdrawals.sumActiveOccupied(kolId));
          BigDecimal frozen = funding.frozenCny(kolId).setScale(2, RoundingMode.UNNECESSARY);
          BigDecimal available = balance.subtract(occupied).setScale(2, RoundingMode.UNNECESSARY);
          BigDecimal minimum = minimum();
          boolean kolFrozen = freezes.latest(kolId) != null && freezes.latest(kolId).active();
          return new Conditions(
              "CNY",
              money(minimum),
              money(frozen),
              money(occupied),
              money(balance),
              money(available),
              kolFrozen,
              !kolFrozen && available.compareTo(minimum) >= 0);
        });
  }

  /** Submits one application (12.01/12.02): snapshot payout info, check threshold, occupy FIFO. */
  @Transactional(rollbackFor = Exception.class)
  public Detail apply(KolAccountDTO kol, ApplyCommand command) {
    if (kol == null || kol.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    UUID requestId = parseUuid(command == null ? null : command.idempotencyKey());
    BigDecimal amount = money(command.amountCny());
    String note = text(command.note(), 1000);
    String fingerprint = applyFingerprint(requestId, amount, command);
    return TenantScope.callCompany(
        kol.tenantId(),
        () -> {
          Long existing = withdrawals.findIdByRequest(kol.id(), requestId);
          if (existing != null) {
            return returnExistingRequest(kol, existing, requestId, fingerprint);
          }
          if (!kols.lockKol(kol.id())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          existing = withdrawals.findIdByRequest(kol.id(), requestId);
          if (existing != null) {
            return returnExistingRequest(kol, existing, requestId, fingerprint);
          }
          if (isFrozen(kol.id())) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_KOL_FROZEN);
          }
          if (command.previousId() != null) {
            requireRejectedPrevious(kol.id(), command.previousId());
          }
          AccountSnapshot snapshot = resolveAccount(kol.tenantId(), kol.id(), command);
          requireFunding(kol.id(), amount);
          Instant now = now();
          long id = IdWorker.getId();
          String applicationNo = nextApplicationNo(id, now);
          int created;
          try {
            created =
                withdrawals.insert(
                    new WithdrawalWrite.Insert(
                        id,
                        kol.id(),
                        applicationNo,
                        requestId,
                        fingerprint,
                        command.previousId(),
                        amount,
                        snapshot.accountId(),
                        snapshot.type(),
                        snapshot.cipher(),
                        snapshot.masked(),
                        snapshot.currency(),
                        snapshot.network(),
                        snapshot.holder(),
                        snapshot.qrFileId(),
                        note,
                        kol.id(),
                        now));
          } catch (DuplicateKeyException exception) {
            return returnExistingRequest(
                kol, withdrawals.findIdByRequest(kol.id(), requestId), requestId, fingerprint);
          }
          if (created == 0) {
            Long duplicate = withdrawals.findIdByRequest(kol.id(), requestId);
            if (duplicate == null) {
              throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
            }
            return returnExistingRequest(kol, duplicate, requestId, fingerprint);
          }
          allocate(id, kol.id(), amount, now);
          if (snapshot.qrFileId() != null && snapshot.accountId() == null) {
            files.claimQrFile(kol.id(), snapshot.qrFileId(), id);
          }
          insertHistory(id, "SUBMITTED", kol.id(), kol.name(), "KOL", note, now);
          insertOutbox(id, applicationNo, "SUBMITTED", now);
          return kolDetail(kol.tenantId(), kol.id(), id);
        });
  }

  /** Creator withdrawal before the review; the money stops being occupied. */
  @Transactional(rollbackFor = Exception.class)
  public Detail withdraw(KolAccountDTO kol, long id) {
    if (kol == null || kol.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return TenantScope.callCompany(
        kol.tenantId(),
        () -> {
          WithdrawalRow.Head head = requireHead(id);
          if (head.kolId() != kol.id()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          if (withdrawals.lockById(id) == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          Instant now = now();
          int changed = withdrawals.withdraw(new WithdrawalWrite.Withdraw(id, kol.id(), now));
          if (changed != 1) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
          }
          insertHistory(id, "WITHDRAWN", kol.id(), kol.name(), "KOL", null, now);
          return kolDetail(kol.tenantId(), kol.id(), id);
        });
  }

  /** The creator's own applications, newest first. */
  @Transactional(readOnly = true)
  public PageResult<KolRow> pageKol(long tenantId, long kolId, int page, int size) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          requirePage(page, size);
          WithdrawalPageQuery query =
              new WithdrawalPageQuery(null, kolId, null, null, size, (long) (page - 1) * size);
          Long total = withdrawals.count(query);
          List<KolRow> rows = withdrawals.page(query).stream().map(head -> toKolRow(head)).toList();
          return new PageResult<>(rows, total == null ? 0L : total, page, size);
        });
  }

  // ------------------------------------------------------------------ Admin side

  /** Review decision (12.02): approve moves to PENDING_PAYMENT, reject needs a reason. */
  @Transactional(rollbackFor = Exception.class)
  public Detail review(AccountDTO user, long id, String decision, String note) {
    long tenantId = requireTenant(user);
    requireAnyPermission(user, "withdrawal:review", "prepay:pay");
    return TenantScope.callCompany(tenantId, () -> reviewInCompany(user, id, decision, note));
  }

  private Detail reviewInCompany(AccountDTO user, long id, String decision, String note) {
    WithdrawalRow row = requireRowLocked(id);
    KolRefDTO kol = requireKol(row.kolId());
    requireKolScope(user, kol);
    boolean pending = "PENDING_REVIEW".equals(row.status());
    boolean awaitingPayment = "PENDING_PAYMENT".equals(row.status());
    if (!pending && !awaitingPayment) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    // Before payment only the pay permission may reject (design §4.8 step 4); the review queue is
    // withdrawal:review. The reviewer never reviews their own submission (same rule as prepay).
    if (pending) {
      requirePermission(user, "withdrawal:review");
    } else {
      requirePermission(user, "prepay:pay");
    }
    if (row.actorId() == user.id()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    boolean approve = "APPROVE".equals(decision);
    if (!approve && !"REJECT".equals(decision)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (approve && awaitingPayment) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    String normalized = text(note, 1000);
    if (!approve && (normalized == null || normalized.isEmpty())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Instant now = now();
    String actorRole = pending ? "REVIEW" : "PAYMENT";
    int changed =
        withdrawals.decide(
            new WithdrawalWrite.Decide(
                id,
                approve ? "PENDING_PAYMENT" : "REJECTED",
                row.status(),
                user.id(),
                now,
                normalized));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    insertHistory(
        id,
        approve ? "APPROVED" : "REJECTED",
        user.id(),
        user.nickname(),
        actorRole,
        normalized,
        now);
    insertOutbox(id, row.applicationNo(), approve ? "APPROVED" : "REJECTED", now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Withdrawal",
        approve ? "Approve" : "Reject",
        "PUT",
        "/admin-api/commission/withdrawals/" + id + "/review",
        "{\"applicationNo\":\"" + row.applicationNo() + "\",\"decision\":\"" + decision + "\"}");
    return detail(user, id);
  }

  /**
   * Payment registration (13.01): one successful payment per task, voucher required, and the wallet
   * ledger is written in the same transaction.
   */
  @Transactional(rollbackFor = Exception.class)
  public Detail pay(AccountDTO user, long id, PaymentCommand command, UploadedFile voucher) {
    long tenantId = requireTenant(user);
    requirePermission(user, "prepay:pay");
    UUID requestId = parseUuid(command == null ? null : command.requestId());
    BigDecimal amount = money(command.amountCny());
    BigDecimal actualAmount = money(command.actualAmount());
    String actualCurrency = currency(command.actualCurrency());
    String method = textRequired(command.method(), 100, "method");
    String financeNote = text(command.financeNote(), 1000);
    String conversionNote = text(command.conversionNote(), 1000);
    Instant paidAt = payTime(command.paidAt());
    if (voucher == null || voucher.content() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (!"CNY".equals(actualCurrency) && (conversionNote == null || conversionNote.isEmpty())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String fingerprint =
        paymentFingerprint(
            id,
            requestId,
            amount,
            actualAmount,
            actualCurrency,
            paidAt,
            method,
            financeNote,
            conversionNote,
            voucher.content());
    return TenantScope.callCompany(
        tenantId,
        () ->
            payInCompany(
                user,
                id,
                requestId,
                amount,
                actualAmount,
                actualCurrency,
                paidAt,
                method,
                financeNote,
                conversionNote,
                fingerprint,
                voucher));
  }

  private Detail payInCompany(
      AccountDTO user,
      long id,
      UUID requestId,
      BigDecimal amount,
      BigDecimal actualAmount,
      String actualCurrency,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      String fingerprint,
      UploadedFile voucher) {
    if (paymentRecorded(requestId, fingerprint)) {
      return detail(user, id);
    }
    WithdrawalRow row = requireRowLocked(id);
    if (paymentRecorded(requestId, fingerprint)) {
      return detail(user, id);
    }
    // One successful payment per task (13.01): a new request on a paid task is the duplicate
    // case; a replay of the same request id already returned above.
    if (withdrawals.findPayment(id) != null) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT);
    }
    if (!"PENDING_PAYMENT".equals(row.status())) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    if (row.amountCny().compareTo(amount) != 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    // A same-currency shortfall needs an explanation; foreign amounts carry the conversion note.
    if ("CNY".equals(actualCurrency)
        && actualAmount.compareTo(amount) != 0
        && (financeNote == null || financeNote.isEmpty())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (nz(withdrawals.sumAllocated(id)).compareTo(amount) != 0) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    if (voucher.content().length > MAX_VOUCHER_BYTES) {
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    }
    String contentType = PrepayFileRules.contentType(voucher.content());
    Long voucherId =
        files.uploadWithdrawalVoucher(
            user.tenantId(), user.id(), id, voucher.fileName(), voucher.content());
    Instant now = now();
    int inserted;
    try {
      inserted =
          withdrawals.insertPayment(
              new WithdrawalWrite.Payment(
                  IdWorker.getId(),
                  id,
                  requestId,
                  fingerprint,
                  amount,
                  actualAmount,
                  actualCurrency,
                  paidAt,
                  method,
                  financeNote,
                  conversionNote,
                  voucherId,
                  user.id(),
                  user.nickname(),
                  "PAYMENT",
                  now));
    } catch (DuplicateKeyException exception) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT);
    }
    if (inserted != 1) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT);
    }
    int changed = withdrawals.markPaid(new WithdrawalWrite.Paid(id, financeNote, user.id(), now));
    if (changed != 1) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    wallet.postWithdrawalDebit(user.tenantId(), row.kolId(), id, amount, paidAt, user.id());
    insertHistory(id, "PAID", user.id(), user.nickname(), "PAYMENT", financeNote, now);
    insertOutbox(id, row.applicationNo(), "PAID", now);
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "Withdrawal",
        "Record payment",
        "PUT",
        "/admin-api/commission/withdrawals/" + id + "/payment",
        "{\"applicationNo\":\""
            + row.applicationNo()
            + "\",\"voucherFileId\":\""
            + voucherId
            + "\",\"contentType\":\""
            + contentType
            + "\"}");
    return detail(user, id);
  }

  /** Admin list (13.02): review and read-only viewers; OWN scope sees only own creators. */
  @Transactional(readOnly = true)
  public PageResult<AdminRow> page(AccountDTO user, Filter filter) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    requirePage(filter.page(), filter.size());
    String status = statusOrNull(filter.status(), STATUSES);
    return TenantScope.callCompany(tenantId, () -> pageInCompany(user, filter, status));
  }

  private PageResult<AdminRow> pageInCompany(AccountDTO user, Filter filter, String status) {
    Long kolId = null;
    String kolCode = text(filter.kolCode(), 64);
    if (kolCode != null) {
      KolRefDTO match = kols.getByCode(kolCode);
      if (match == null || !kolCode.equals(match.kolCode())) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
      kolId = match.id();
    }
    List<Long> ownIds = ownKolIds(user);
    if (ownIds != null && ownIds.isEmpty()) {
      return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
    }
    WithdrawalPageQuery query =
        new WithdrawalPageQuery(
            text(filter.applicationNo(), 64),
            kolId,
            status,
            ownIds,
            filter.size(),
            (long) (filter.page() - 1) * filter.size());
    Long total = withdrawals.count(query);
    List<WithdrawalRow.Head> heads = withdrawals.page(query);
    Map<Long, KolRefDTO> kolById = kolsById(heads.stream().map(WithdrawalRow.Head::kolId).toList());
    Map<Long, String> bdNames =
        namesById(
            kolById.values().stream().map(KolRefDTO::bdUserId).filter(Objects::nonNull).toList());
    List<AdminRow> rows =
        heads.stream()
            .map(
                head -> {
                  KolRefDTO kol = kolById.get(head.kolId());
                  return new AdminRow(
                      head.id(),
                      head.applicationNo(),
                      head.kolId(),
                      kol == null ? null : kol.kolCode(),
                      kol == null ? null : kol.name(),
                      kol == null ? null : kol.bdUserId(),
                      kol == null || kol.bdUserId() == null ? null : bdNames.get(kol.bdUserId()),
                      "COMMISSION_WITHDRAWAL",
                      money(head.amountCny()),
                      "CNY",
                      head.status(),
                      head.createTime(),
                      null,
                      null);
                })
            .toList();
    return new PageResult<>(rows, total == null ? 0L : total, filter.page(), filter.size());
  }

  /** Detail (13.04): masked payout, finance notes only for finance viewers, audit on reveal. */
  @Transactional(readOnly = true)
  public Detail detail(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    return TenantScope.callCompany(tenantId, () -> detailInCompany(user, id));
  }

  /** Creator's own detail: full payout snapshot, finance notes hidden. */
  @Transactional(readOnly = true)
  public Detail kolDetail(long tenantId, long kolId, long id) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          WithdrawalRow row = requireRow(id);
          if (row.kolId() != kolId) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          return toDetail(row, null, true, false);
        });
  }

  private Detail detailInCompany(AccountDTO user, long id) {
    WithdrawalRow row = requireRow(id);
    KolRefDTO kol = requireKol(row.kolId());
    requireKolScope(user, kol);
    return toDetail(row, kol, false, financeVisible(user));
  }

  private Detail toDetail(
      WithdrawalRow row, KolRefDTO kol, boolean kolViewer, boolean financeVisible) {
    WithdrawalRows.PaymentRow payment = withdrawals.findPayment(row.id());
    List<HistoryItem> history = history(row.id(), kolViewer, financeVisible);
    String bdName = null;
    Long bdId = null;
    if (kol != null) {
      bdId = kol.bdUserId();
      bdName = bdId == null ? null : namesById(List.of(bdId)).get(bdId);
    }
    Long allocationCount = withdrawals.countAllocations(row.id());
    return new Detail(
        row.id(),
        row.applicationNo(),
        row.kolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        bdId,
        bdName,
        "COMMISSION_WITHDRAWAL",
        money(row.amountCny()),
        "CNY",
        row.status(),
        row.createTime(),
        row.note(),
        row.reviewNote(),
        financeVisible ? row.financeNote() : null,
        row.previousId(),
        allocationCount == null ? 0L : allocationCount,
        accountView(row, kolViewer),
        history,
        payment == null ? null : paymentView(payment, financeVisible));
  }

  /** Full payout snapshot for reviewers and payers only; every read is audited (13.04). */
  @Transactional(rollbackFor = Exception.class)
  public Recipient recipient(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    if (!user.effectivePermissions().contains("withdrawal:review")
        && !user.effectivePermissions().contains("prepay:pay")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return TenantScope.callCompany(
        tenantId,
        () -> {
          WithdrawalRow row = requireRow(id);
          requireKolScope(user, requireKol(row.kolId()));
          audit.recordTenantOperation(
              user.tenantId(),
              user.id(),
              "Withdrawal",
              "View recipient",
              "/admin-api/commission/withdrawals/" + id + "/recipient",
              "{\"applicationNo\":\"" + row.applicationNo() + "\"}");
          return new Recipient(
              row.accountType(),
              cipher.decrypt(row.accountCipher()),
              row.accountHolder(),
              row.accountCurrency(),
              row.accountNetwork());
        });
  }

  /** Order items occupied by one application (13.04), paged, immutable snapshots. */
  @Transactional(readOnly = true)
  public PageResult<WithdrawalRows.AllocationRow> orders(
      AccountDTO user, long id, int page, int size) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    requirePage(page, size);
    return TenantScope.callCompany(
        tenantId,
        () -> {
          WithdrawalRow row = requireRow(id);
          requireKolScope(user, requireKol(row.kolId()));
          Long total = withdrawals.countAllocations(id);
          List<WithdrawalRows.AllocationRow> items =
              withdrawals.pageAllocations(id, size, (long) (page - 1) * size);
          return new PageResult<>(items, total == null ? 0L : total, page, size);
        });
  }

  /** Streams the payment voucher of one withdrawal; every download keeps the detail permission. */
  @Transactional(readOnly = true)
  public BizFileService.FileDTO voucher(AccountDTO user, long id) {
    long tenantId = requireTenant(user);
    requireDetailPermission(user);
    return TenantScope.callCompany(
        tenantId,
        () -> {
          WithdrawalRow row = requireRow(id);
          requireKolScope(user, requireKol(row.kolId()));
          WithdrawalRows.PaymentRow payment = withdrawals.findPayment(id);
          if (payment == null || payment.voucherFileId() == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          BizFileService.AttachedFile file = files.attachedFile(payment.voucherFileId());
          if (file.bizType() != FileBizType.WITHDRAWAL_VOUCHER
              || file.bizId() == null
              || file.bizId() != id) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          audit.recordTenantOperation(
              user.tenantId(),
              user.id(),
              "Withdrawal",
              "Download voucher",
              "/admin-api/commission/withdrawals/" + id + "/voucher",
              "{\"applicationNo\":\"" + row.applicationNo() + "\"}");
          return file.download();
        });
  }

  /** The KOL's own payment voucher; anyone else's file is a 404. */
  @Transactional(readOnly = true)
  public BizFileService.FileDTO kolVoucher(long tenantId, long kolId, long id) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          WithdrawalRows.PaymentRow payment = withdrawals.findPayment(id);
          if (payment == null || payment.voucherFileId() == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          WithdrawalRow row = requireRow(id);
          if (row.kolId() != kolId || !"PAID".equals(row.status())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          BizFileService.AttachedFile file = files.attachedFile(payment.voucherFileId());
          if (file.bizType() != FileBizType.WITHDRAWAL_VOUCHER
              || file.bizId() == null
              || file.bizId() != id) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          return file.download();
        });
  }

  /** Occupations of order items for the order chain (09.04); foreign items are skipped. */
  @Transactional(readOnly = true)
  public List<Occupation> occupations(AccountDTO user, List<Long> orderItemIds) {
    long tenantId = requireTenant(user);
    if (!user.effectivePermissions().contains("order:view")
        && !user.effectivePermissions().contains("commission:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    if (orderItemIds == null || orderItemIds.isEmpty() || orderItemIds.size() > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        tenantId,
        () -> {
          Map<Long, OrderItemInfoDTO> itemById = new LinkedHashMap<>();
          for (OrderItemInfoDTO item : orderItems.listByIds(orderItemIds)) {
            itemById.put(item.id(), item);
          }
          if (itemById.isEmpty()) {
            return List.of();
          }
          Set<Long> kolIds =
              itemById.values().stream().map(OrderItemInfoDTO::kolId).collect(Collectors.toSet());
          Map<Long, KolRefDTO> kolById = kolsById(kolIds);
          List<Long> visible = new ArrayList<>();
          for (Long itemId : orderItemIds) {
            OrderItemInfoDTO item = itemById.get(itemId);
            if (item == null) {
              continue;
            }
            KolRefDTO kol = item.kolId() == null ? null : kolById.get(item.kolId());
            if (kol == null || !user.ownScope() || user.id().equals(kol.bdUserId())) {
              visible.add(itemId);
            }
          }
          if (visible.isEmpty()) {
            return List.of();
          }
          return withdrawals.listOccupationsByOrderItems(visible).stream()
              .map(
                  row ->
                      new Occupation(
                          row.orderItemId(),
                          row.withdrawalId(),
                          row.applicationNo(),
                          row.status(),
                          money(row.occupiedCny()),
                          money(row.sourceReleasedCny()),
                          money(row.remainingSnapshotCny()),
                          row.submittedAt(),
                          row.paidAt()))
              .toList();
        });
  }

  /** CSV export of the withdrawal list: data:export AND the list permission. */
  @Transactional(readOnly = true)
  public byte[] export(AccountDTO user, Filter filter) {
    requireTenant(user);
    if (user.role() != Role.SUPER_ADMIN && !user.permissions().contains("data:export")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    requireDetailPermission(user);
    return TenantScope.callCompany(user.tenantId(), () -> exportInCompany(user, filter));
  }

  private byte[] exportInCompany(AccountDTO user, Filter filter) {
    StringBuilder csv =
        new StringBuilder(
            "\uFEFF申请编号,达人编号,达人名称,金额(CNY),状态,收款方式,脱敏收款账户,申请时间,审核备注,"
                + "付款金额(CNY),支付时间,实际支付金额,实际支付币种\r\n");
    PageResult<AdminRow> first =
        page(user, new Filter(filter.applicationNo(), filter.kolCode(), filter.status(), 1, 100));
    if (first.total() > 100000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    appendExportRows(csv, first.items());
    for (int page = 2; (long) (page - 1) * 100 < first.total(); page++) {
      appendExportRows(
          csv,
          page(
                  user,
                  new Filter(filter.applicationNo(), filter.kolCode(), filter.status(), page, 100))
              .items());
    }
    audit.recordTenantOperation(
        user.tenantId(),
        user.id(),
        "Withdrawal",
        "Export tasks",
        "/admin-api/commission/withdrawals/export",
        "{\"rowCount\":" + first.total() + "}");
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void appendExportRows(StringBuilder csv, List<AdminRow> rows) {
    for (AdminRow row : rows) {
      WithdrawalRow full = withdrawals.findRow(row.id());
      WithdrawalRows.PaymentRow payment = withdrawals.findPayment(row.id());
      String[] fields = {
        row.applicationNo(),
        row.kolCode() == null ? "" : row.kolCode(),
        row.kolName() == null ? "" : row.kolName(),
        row.amountCny(),
        row.status(),
        full == null ? "" : full.accountType(),
        full == null ? "" : full.accountMasked(),
        row.createdAt().toString(),
        full == null || full.reviewNote() == null ? "" : full.reviewNote(),
        payment == null ? "" : money(payment.amountCny()),
        payment == null ? "" : payment.paidAt().toString(),
        payment == null ? "" : money(payment.actualAmount()),
        payment == null ? "" : payment.actualCurrency()
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

  // ------------------------------------------------------------------ Freeze

  /** Freezes a creator's new withdrawal applications (11.03/§4.8); reason is required. */
  @Transactional(rollbackFor = Exception.class)
  public void freeze(AccountDTO user, long kolId, String reason) {
    long tenantId = requireTenant(user);
    requirePermission(user, "commission:freeze");
    String normalized = text(reason, 500);
    if (normalized == null || normalized.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    TenantScope.runCompany(
        tenantId,
        () -> {
          requireKol(kolId);
          if (freezes.latest(kolId) != null && freezes.latest(kolId).active()) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_FROZEN_ALREADY);
          }
          Instant now = now();
          try {
            freezes.insert(
                new WithdrawalWrite.Freeze(IdWorker.getId(), kolId, normalized, user.id(), now));
          } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_FROZEN_ALREADY);
          }
          audit.recordTenantWriteOperation(
              user.tenantId(),
              user.id(),
              "Withdrawal",
              "Freeze creator",
              "POST",
              "/admin-api/commission/withdrawal-freezes",
              "{\"kolId\":\"" + kolId + "\"}");
        });
  }

  /** Lifts the freeze so the creator can apply again; history stays. */
  @Transactional(rollbackFor = Exception.class)
  public void resume(AccountDTO user, long kolId, String reason) {
    long tenantId = requireTenant(user);
    requirePermission(user, "commission:freeze");
    String normalized = text(reason, 500);
    TenantScope.runCompany(
        tenantId,
        () -> {
          requireKol(kolId);
          Long activeId = freezes.lockActive(kolId);
          if (activeId == null) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_NOT_FROZEN);
          }
          freezes.resume(new WithdrawalWrite.Resume(activeId, normalized, user.id(), now()));
          audit.recordTenantWriteOperation(
              user.tenantId(),
              user.id(),
              "Withdrawal",
              "Resume creator",
              "PUT",
              "/admin-api/commission/withdrawal-freezes/" + kolId + "/resume",
              "{\"kolId\":\"" + kolId + "\"}");
        });
  }

  // ------------------------------------------------------------------ Finance task list

  /** Unified finance tasks (13.03): commission withdrawals and creator prepays on one page. */
  @Transactional(readOnly = true)
  public PageResult<FinanceTask> financeTasks(AccountDTO user, FinanceFilter filter) {
    long tenantId = requireTenant(user);
    requireFinanceTaskPermission(user);
    requirePage(filter.page(), filter.size());
    String status = statusOrNull(filter.status(), TASK_STATUSES);
    String type = typeOrNull(filter.type());
    return TenantScope.callCompany(
        tenantId, () -> financeTasksInCompany(user, filter, status, type));
  }

  private PageResult<FinanceTask> financeTasksInCompany(
      AccountDTO user, FinanceFilter filter, String status, String type) {
    Long kolId = null;
    String kolCode = text(filter.kolCode(), 64);
    if (kolCode != null) {
      KolRefDTO match = kols.getByCode(kolCode);
      if (match == null || !kolCode.equals(match.kolCode())) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
      kolId = match.id();
    }
    List<Long> ownIds = ownKolIds(user);
    if (ownIds != null && ownIds.isEmpty()) {
      return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
    }
    List<String> withdrawalStatuses = status == null ? null : withdrawalStatusesFor(status);
    List<String> prepayStatuses = status == null ? null : prepayStatusesFor(status);
    int limit = filter.page() * filter.size();
    FinanceTaskQuery query =
        new FinanceTaskQuery(
            text(filter.applicationNo(), 64),
            kolId,
            status,
            type,
            ownIds,
            withdrawalStatuses,
            prepayStatuses,
            limit);
    long total = 0;
    List<FinanceTaskRow> merged = new ArrayList<>();
    if (type == null || "COMMISSION_WITHDRAWAL".equals(type)) {
      total += nz(withdrawals.countWithdrawalTasks(query));
      merged.addAll(withdrawals.pageWithdrawalTasks(query));
    }
    if (type == null || "PREPAY".equals(type)) {
      total += nz(withdrawals.countPrepayTasks(query));
      merged.addAll(withdrawals.pagePrepayTasks(query));
    }
    merged.sort(
        Comparator.comparing(FinanceTaskRow::createdAt)
            .thenComparing(FinanceTaskRow::id)
            .reversed());
    int from = Math.min((filter.page() - 1) * filter.size(), merged.size());
    int to = Math.min(from + filter.size(), merged.size());
    return new PageResult<>(
        enrichTasks(merged.subList(from, to)), total, filter.page(), filter.size());
  }

  /** 13.03 wants the creator name and id on every row; names resolve once per page. */
  private List<FinanceTask> enrichTasks(List<FinanceTaskRow> rows) {
    Map<Long, KolRefDTO> kolById = kolsById(rows.stream().map(FinanceTaskRow::kolId).toList());
    Map<Long, String> bdNames =
        namesById(
            kolById.values().stream().map(KolRefDTO::bdUserId).filter(Objects::nonNull).toList());
    return rows.stream()
        .map(
            row -> {
              KolRefDTO kol = kolById.get(row.kolId());
              return new FinanceTask(
                  row.id(),
                  row.taskNo(),
                  row.kolId(),
                  kol == null ? null : kol.kolCode(),
                  kol == null ? null : kol.name(),
                  kol == null ? null : kol.bdUserId(),
                  kol == null || kol.bdUserId() == null ? null : bdNames.get(kol.bdUserId()),
                  row.taskType(),
                  row.sourceStatus(),
                  row.status(),
                  money(row.amountCny()),
                  "CNY",
                  row.createdAt());
            })
        .toList();
  }

  // ------------------------------------------------------------------ Internals

  private Detail returnExistingRequest(
      KolAccountDTO kol, Long existingId, UUID requestId, String fingerprint) {
    if (existingId == null) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_STATE_CONFLICT);
    }
    WithdrawalRow existing = requireRow(existingId);
    if (!fingerprint.equals(existing.fingerprint())) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_REQUEST_CONFLICT);
    }
    return kolDetail(kol.tenantId(), kol.id(), existingId);
  }

  private record AccountSnapshot(
      Long accountId,
      String type,
      String cipher,
      String masked,
      String currency,
      String network,
      String holder,
      Long qrFileId) {}

  /**
   * Resolves the payout snapshot (12.01): an inline account typed for this application, or one
   * approved account of this KOL. Inline accounts are encrypted here so the stored cipher belongs
   * to this application alone.
   */
  private AccountSnapshot resolveAccount(long tenantId, long kolId, ApplyCommand command) {
    if (command.payoutAccountId() != null) {
      PayoutAccountDO account = payoutAccounts.selectById(command.payoutAccountId());
      if (account == null
          || account.getTenantId() == null
          || account.getTenantId() != tenantId
          || !Long.valueOf(kolId).equals(account.getKolId())
          || account.getStatus() != PayoutAccountStatus.APPROVED) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
      return new AccountSnapshot(
          account.getId(),
          account.getAccountType().name(),
          account.getAccountCipher(),
          account.getAccountMasked(),
          account.getCurrency(),
          account.getUsdtNetwork() == null ? null : account.getUsdtNetwork().name(),
          account.getHolderName(),
          account.getQrFileId());
    }
    AccountInput input = command.account();
    if (input == null || input.type() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String account = requireAccountValue(input.account(), 128);
    String holder = holder(input);
    if ("PAYPAL".equals(input.type())) {
      if (input.network() != null
          || input.qrFileId() != null
          || !EMAIL.matcher(account).matches()) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      return new AccountSnapshot(
          null,
          "PAYPAL",
          cipher.encrypt(account),
          AccountMasker.mask(account),
          requireAccountValue(input.currency(), 8),
          null,
          holder,
          null);
    }
    if ("USDT".equals(input.type())) {
      if (!account.matches("[A-Za-z0-9]+") || input.currency() != null) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      if (input.network() == null) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      try {
        UsdtNetwork.valueOf(input.network());
      } catch (IllegalArgumentException exception) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      return new AccountSnapshot(
          null,
          "USDT",
          cipher.encrypt(account),
          AccountMasker.mask(account),
          null,
          input.network(),
          holder,
          input.qrFileId());
    }
    throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  private static String holder(AccountInput input) {
    String holder = text(input.holder(), 64);
    if (holder == null || holder.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return holder;
  }

  private static String requireAccountValue(String value, int max) {
    String account = text(value, max);
    if (account == null || account.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return account;
  }

  private void requireRejectedPrevious(long kolId, long previousId) {
    WithdrawalRow previous = withdrawals.findRow(previousId);
    if (previous == null || previous.kolId() != kolId || !"REJECTED".equals(previous.status())) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_PREVIOUS_INVALID);
    }
  }

  private boolean isFrozen(long kolId) {
    WithdrawalRows.FreezeRow row = freezes.latest(kolId);
    return row != null && row.active();
  }

  /**
   * Checks the funding rows cover the amount before writing anything: available wallet money and
   * released order items can drift apart only until task 62 merges.
   */
  private void requireFunding(long kolId, BigDecimal amount) {
    wallets.insertIgnore(IdWorker.getId(), kolId, now());
    BigDecimal balance = requireBalance(wallets.lockBalance(kolId));
    BigDecimal occupied = nz(withdrawals.sumActiveOccupied(kolId));
    BigDecimal available = balance.subtract(occupied).setScale(2, RoundingMode.UNNECESSARY);
    BigDecimal minimum = minimum();
    if (amount.compareTo(minimum) < 0) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_BELOW_MINIMUM);
    }
    if (amount.compareTo(available) > 0) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE);
    }
  }

  /** FIFO allocation over released order items (13.04: 占用金额 + 剩余快照). */
  private void allocate(long withdrawalId, long kolId, BigDecimal amount, Instant now) {
    List<FundingRow> rows = funding.listReleasedRows(kolId);
    List<Long> itemIds = rows.stream().map(FundingRow::orderItemId).toList();
    Map<Long, BigDecimal> occupiedByItem = new HashMap<>();
    if (!itemIds.isEmpty()) {
      for (WithdrawalMapper.ItemOccupation occupation :
          withdrawals.sumOccupiedByItem(kolId, itemIds)) {
        occupiedByItem.put(occupation.orderItemId(), occupation.occupiedCny());
      }
    }
    Map<Long, OrderItemInfoDTO> itemById = new HashMap<>();
    for (OrderItemInfoDTO item : orderItems.listByIds(itemIds)) {
      itemById.put(item.id(), item);
    }
    Map<Long, String> imageByProduct = new HashMap<>();
    Set<Long> productIds =
        itemById.values().stream()
            .map(OrderItemInfoDTO::productId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (!productIds.isEmpty()) {
      for (ProductAccessApi.ProductRefDTO product : products.listProducts(productIds)) {
        imageByProduct.put(product.id(), product.imageUrl());
      }
    }
    BigDecimal remaining = amount;
    for (FundingRow row : rows) {
      if (remaining.signum() == 0) {
        break;
      }
      OrderItemInfoDTO item = itemById.get(row.orderItemId());
      if (item == null) {
        // The order line is gone, so its money cannot be displayed on the finance pages; it stays
        // in the wallet and can be withdrawn once the settlement side reconciles it (task 62).
        continue;
      }
      BigDecimal itemRemaining =
          row.releasedCny().subtract(nz(occupiedByItem.get(row.orderItemId())));
      if (itemRemaining.signum() <= 0) {
        continue;
      }
      BigDecimal take = remaining.min(itemRemaining);
      withdrawals.insertAllocation(
          new WithdrawalWrite.Allocation(
              IdWorker.getId(),
              withdrawalId,
              row.orderItemId(),
              item.orderNo(),
              item.itemName(),
              item.skuName(),
              item.productId() == null ? null : imageByProduct.get(item.productId()),
              take.setScale(2, RoundingMode.UNNECESSARY),
              itemRemaining.subtract(take).setScale(2, RoundingMode.UNNECESSARY),
              row.releasedCny().setScale(2, RoundingMode.UNNECESSARY),
              now));
      remaining = remaining.subtract(take);
    }
    if (remaining.signum() > 0) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_INSUFFICIENT_AVAILABLE);
    }
  }

  private AccountView accountView(WithdrawalRow row, boolean kolViewer) {
    String account = kolViewer ? cipher.decrypt(row.accountCipher()) : row.accountMasked();
    String holder = kolViewer ? row.accountHolder() : AccountMasker.mask(row.accountHolder());
    return new AccountView(
        row.accountId(),
        row.accountType(),
        account,
        holder,
        row.accountCurrency(),
        row.accountNetwork(),
        row.accountQrFileId());
  }

  private List<HistoryItem> history(long id, boolean kolViewer, boolean financeVisible) {
    List<HistoryItem> items = new ArrayList<>();
    for (WithdrawalRows.HistoryRow row : withdrawals.timeline(id)) {
      String note = row.note();
      if (kolViewer && !"REJECTED".equals(row.action()) && !"SUBMITTED".equals(row.action())) {
        note = null;
      }
      if (!financeVisible && "PAID".equals(row.action())) {
        note = null;
      }
      items.add(
          new HistoryItem(
              row.action(), row.actorId(), row.actorName(), row.actorRole(), note, row.at()));
    }
    return List.copyOf(items);
  }

  private PaymentView paymentView(WithdrawalRows.PaymentRow row, boolean financeVisible) {
    return new PaymentView(
        money(row.amountCny()),
        money(row.actualAmount()),
        row.actualCurrency(),
        row.paidAt(),
        row.method(),
        financeVisible ? row.financeNote() : null,
        financeVisible ? row.conversionNote() : null,
        row.voucherFileId(),
        row.operatorName(),
        row.operatorRole());
  }

  private KolRow toKolRow(WithdrawalRow.Head head) {
    WithdrawalRow row = withdrawals.findRow(head.id());
    WithdrawalRows.PaymentRow payment = withdrawals.findPayment(head.id());
    return new KolRow(
        head.id(),
        head.applicationNo(),
        money(head.amountCny()),
        "CNY",
        head.status(),
        head.createTime(),
        "REJECTED".equals(head.status()) && row != null ? row.reviewNote() : null,
        payment == null ? null : payment.paidAt(),
        payment == null ? null : money(payment.actualAmount()),
        payment == null ? null : payment.actualCurrency(),
        row == null ? null : accountView(row, true));
  }

  private void insertHistory(
      long id,
      String action,
      Long actorId,
      String actorName,
      String actorRole,
      String note,
      Instant now) {
    withdrawals.insertHistory(
        new WithdrawalWrite.History(
            IdWorker.getId(), id, action, actorId, actorName, actorRole, note, now));
  }

  private void insertOutbox(long id, String applicationNo, String type, Instant now) {
    String escaped = applicationNo.replace("\\", "\\\\").replace("\"", "\\\"");
    withdrawals.insertOutbox(
        new WithdrawalWrite.Outbox(
            IdWorker.getId(), id, type, "{\"applicationNo\":\"" + escaped + "\"}", now, now));
  }

  private String nextApplicationNo(long id, Instant now) {
    LocalDate date = now.atZone(NUMBER_ZONE).toLocalDate();
    Integer sequence = withdrawals.nextSequence(id, date, now);
    if (sequence == null) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    return "CW" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("%06d", sequence);
  }

  private boolean paymentRecorded(UUID requestId, String fingerprint) {
    String prior = withdrawals.findPaymentFingerprint(requestId);
    if (prior == null) {
      return false;
    }
    if (!fingerprint.equals(prior)) {
      throw new BusinessException(ErrorCode.WITHDRAWAL_PAYMENT_CONFLICT);
    }
    return true;
  }

  private WithdrawalRow.Head requireHead(long id) {
    WithdrawalRow.Head head = withdrawals.findHead(id);
    if (head == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return head;
  }

  private WithdrawalRow requireRow(long id) {
    WithdrawalRow row = withdrawals.findRow(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  private WithdrawalRow requireRowLocked(long id) {
    WithdrawalRow row = requireRow(id);
    if (withdrawals.lockById(id) == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return row;
  }

  private KolRefDTO requireKol(long kolId) {
    List<KolRefDTO> rows = kols.listByIds(List.of(kolId));
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return rows.getFirst();
  }

  private Map<Long, KolRefDTO> kolsById(java.util.Collection<Long> ids) {
    Map<Long, KolRefDTO> byId = new HashMap<>();
    for (KolRefDTO kol : kols.listByIdsIncludingDeleted(ids)) {
      byId.put(kol.id(), kol);
    }
    return byId;
  }

  private Map<Long, String> namesById(java.util.Collection<Long> ids) {
    Map<Long, String> byId = new HashMap<>();
    for (AccountNameDTO name : accountNames.listByIds(ids)) {
      byId.put(name.id(), name.nickname());
    }
    return byId;
  }

  /** OWN-scope viewers (BD accounts) only ever see their own creators (design §3.2). */
  private List<Long> ownKolIds(AccountDTO user) {
    if (!user.ownScope()) {
      return null;
    }
    return kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).distinct().toList();
  }

  private static void requireKolScope(AccountDTO user, KolRefDTO kol) {
    if (user.ownScope() && !user.id().equals(kol.bdUserId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
  }

  private static boolean financeVisible(AccountDTO user) {
    return user.role() == Role.SUPER_ADMIN || user.permissions().contains("prepay:pay");
  }

  private static void requireDetailPermission(AccountDTO user) {
    if (user.role() != Role.SUPER_ADMIN
        && !user.permissions().contains("withdrawal:review")
        && !user.permissions().contains("commission:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static void requireFinanceTaskPermission(AccountDTO user) {
    if (user.role() != Role.SUPER_ADMIN
        && !user.permissions().contains("withdrawal:review")
        && !user.permissions().contains("prepay:review")
        && !user.permissions().contains("prepay:finance-review")
        && !user.permissions().contains("prepay:pay")
        && !user.permissions().contains("commission:view")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
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

  private static long requireTenant(AccountDTO user) {
    if (user == null || user.role() == Role.PLATFORM_ADMIN || user.tenantId() == null) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private BigDecimal minimum() {
    return BigDecimal.valueOf(configs.getInt(MINIMUM_KEY)).setScale(2, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal requireBalance(BigDecimal balance) {
    if (balance == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return balance;
  }

  private static void requirePage(int page, int size) {
    if (page < 1 || size < 1 || size > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  /** Raw withdrawal statuses whose shared lifecycle equals the filter. */
  private static List<String> withdrawalStatusesFor(String unified) {
    return STATUSES.stream()
        .filter(status -> FinanceTaskRow.mapWithdrawalStatus(status).equals(unified))
        .toList();
  }

  /** Raw prepay statuses whose shared lifecycle equals the filter. */
  private static List<String> prepayStatusesFor(String unified) {
    return PREPAY_STATUSES.stream()
        .filter(status -> FinanceTaskRow.mapPrepayStatus(status).equals(unified))
        .toList();
  }

  private static String statusOrNull(String value, List<String> allowed) {
    String status = text(value, 32);
    if (status == null) {
      return null;
    }
    if (!allowed.contains(status)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return status;
  }

  private static String typeOrNull(String value) {
    String type = text(value, 32);
    if (type == null) {
      return null;
    }
    if (!"COMMISSION_WITHDRAWAL".equals(type) && !"PREPAY".equals(type)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return type;
  }

  private static String currency(String value) {
    String currency = text(value, 8);
    if (currency == null || !currency.matches("[A-Z]{3,8}")) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return currency;
  }

  private static String textRequired(String value, int max, String field) {
    String text = text(value, max);
    if (text == null || text.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return text;
  }

  private static String text(String value, int max) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.isEmpty()) {
      return null;
    }
    if (stripped.length() > max) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return stripped;
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

  private static String applyFingerprint(UUID requestId, BigDecimal amount, ApplyCommand command) {
    StringBuilder canonical =
        new StringBuilder()
            .append(requestId)
            .append('|')
            .append(amount.toPlainString())
            .append('|')
            .append(command.previousId() == null ? "" : command.previousId())
            .append('|')
            .append(command.payoutAccountId() == null ? "" : command.payoutAccountId())
            .append('|');
    AccountInput account = command.account();
    if (account != null) {
      canonical
          .append(account.type() == null ? "" : account.type())
          .append('|')
          .append(account.account() == null ? "" : account.account())
          .append('|')
          .append(account.holder() == null ? "" : account.holder())
          .append('|')
          .append(account.currency() == null ? "" : account.currency())
          .append('|')
          .append(account.network() == null ? "" : account.network())
          .append('|')
          .append(account.qrFileId() == null ? "" : account.qrFileId());
    }
    canonical.append('|').append(command.note() == null ? "" : command.note());
    return sha256(canonical.toString());
  }

  private static String paymentFingerprint(
      long id,
      UUID requestId,
      BigDecimal amount,
      BigDecimal actualAmount,
      String actualCurrency,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      byte[] voucher) {
    String canonical =
        id
            + "|"
            + requestId
            + "|"
            + amount.toPlainString()
            + "|"
            + actualAmount.toPlainString()
            + "|"
            + actualCurrency
            + "|"
            + paidAt
            + "|"
            + method
            + "|"
            + (financeNote == null ? "" : financeNote)
            + "|"
            + (conversionNote == null ? "" : conversionNote)
            + "|";
    return sha256ThenBytes(canonical, voucher);
  }

  private static String sha256(String value) {
    return sha256ThenBytes(value, new byte[0]);
  }

  private static String sha256ThenBytes(String value, byte[] suffix) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest.digest(suffix));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
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

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2) : value;
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }
}
