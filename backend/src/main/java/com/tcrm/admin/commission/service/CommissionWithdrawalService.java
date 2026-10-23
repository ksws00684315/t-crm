package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single persistent workflow for commission-only KOL withdrawals. */
@Service
public class CommissionWithdrawalService {
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final List<String> PAYPAL_CURRENCIES = List.of("USD", "EUR", "GBP", "CNY");
  private final JdbcTemplate jdbc;
  private final CommissionWalletService wallet;
  private final CommissionSettlementService settlement;
  private final PayoutAccountService payoutAccounts;
  private final BizFileService files;
  private final SecretCipher cipher;
  private final KolApi kols;
  private final Clock clock;

  public CommissionWithdrawalService(
      JdbcTemplate jdbc,
      CommissionWalletService wallet,
      CommissionSettlementService settlement,
      PayoutAccountService payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      KolApi kols) {
    this(jdbc, wallet, settlement, payoutAccounts, files, cipher, kols, Clock.systemUTC());
  }

  CommissionWithdrawalService(
      JdbcTemplate jdbc,
      CommissionWalletService wallet,
      CommissionSettlementService settlement,
      PayoutAccountService payoutAccounts,
      BizFileService files,
      SecretCipher cipher,
      KolApi kols,
      Clock clock) {
    this.jdbc = jdbc;
    this.wallet = wallet;
    this.settlement = settlement;
    this.payoutAccounts = payoutAccounts;
    this.files = files;
    this.cipher = cipher;
    this.kols = kols;
    this.clock = clock;
  }

  public record AccountSnapshot(
      String id,
      String type,
      String account,
      String currency,
      String network,
      String holder,
      String qrFileId) {}

  public record AccountInput(
      String type, String account, String currency, String network, String holder, Long qrFileId) {}

  public record Request(
      String amount,
      AccountInput account,
      String note,
      Long previousId,
      Long payoutAccountId,
      String idempotencyKey) {}

  public record Payment(
      String actualAmount,
      String actualCurrency,
      String amountCny,
      Instant paidAt,
      String method,
      String conversionNote,
      String financeNote,
      String voucher,
      String withdrawalId) {}

  public record Allocation(
      String id,
      String orderNo,
      String image,
      String product,
      String sku,
      String occupied,
      String remainingSnapshot) {}

  public record History(String action, Instant at, String actor, String note) {}

  public record Withdrawal(
      String id,
      String applicationNo,
      String kolId,
      String kolName,
      String kolCode,
      String type,
      String amount,
      String currency,
      String status,
      Instant createdAt,
      boolean paused,
      String pauseReason,
      AccountSnapshot account,
      String note,
      String reviewNote,
      String financeNote,
      List<Allocation> allocations,
      List<History> history,
      Payment payment,
      String previousId) {}

  public record PaymentRequest(
      String actualAmount,
      String actualCurrency,
      String amountCny,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      Long voucher,
      String idempotencyKey) {}

  public record Filter(String applicationNo, String kol, String status, int page, int size) {}

  public record WithdrawalProduct(String id, String image, String product, String sku) {}

  public record WithdrawalOrder(
      String id,
      String orderNo,
      List<WithdrawalProduct> products,
      String occupied,
      String remainingSnapshot) {}

  @Transactional(readOnly = true)
  public PageResult<WithdrawalOrder> ordersAdmin(
      AccountDTO admin, long withdrawalId, int page, int size) {
    validatePage(page, size);
    detailAdmin(admin, withdrawalId);
    long tenantId = requireTenant(admin);
    String base =
        " FROM crm_commission_withdrawal_allocation a JOIN crm_commission_item_state s ON s.id=a.item_state_id AND s.tenant_id=a.tenant_id JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id WHERE a.tenant_id=? AND a.withdrawal_id=?";
    Long total =
        jdbc.queryForObject(
            "SELECT count(DISTINCT o.id)" + base, Long.class, tenantId, withdrawalId);
    List<Long> orderIds =
        jdbc.query(
            "SELECT o.id" + base + " GROUP BY o.id ORDER BY o.id LIMIT ? OFFSET ?",
            (rs, index) -> rs.getLong(1),
            tenantId,
            withdrawalId,
            size,
            (long) (page - 1) * size);
    List<WithdrawalOrder> items =
        orderIds.stream().map(orderId -> withdrawalOrder(tenantId, withdrawalId, orderId)).toList();
    return new PageResult<>(items, total == null ? 0L : total, page, size);
  }

  private WithdrawalOrder withdrawalOrder(long tenantId, long withdrawalId, long orderId) {
    List<WithdrawalOrder> rows =
        jdbc.query(
            "SELECT o.id,o.weidian_order_id,COALESCE(i.sub_order_id,''),i.item_name,i.sku_name,a.occupied_cny,a.remaining_snapshot_cny FROM crm_commission_withdrawal_allocation a JOIN crm_commission_item_state s ON s.id=a.item_state_id AND s.tenant_id=a.tenant_id JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id JOIN crm_order_item i ON i.id=a.order_item_id AND i.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.withdrawal_id=? AND o.id=? ORDER BY i.id",
            (rs, index) ->
                new WithdrawalOrder(
                    Long.toString(rs.getLong(1)),
                    rs.getString(2),
                    List.of(
                        new WithdrawalProduct(
                            rs.getString(3), null, rs.getString(4), rs.getString(5))),
                    money(rs.getBigDecimal(6)),
                    rs.getBigDecimal(7) == null ? null : money(rs.getBigDecimal(7))),
            tenantId,
            withdrawalId,
            orderId);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    BigDecimal occupied =
        rows.stream()
            .map(row -> new BigDecimal(row.occupied()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean unknownRemaining = rows.stream().anyMatch(row -> row.remainingSnapshot() == null);
    List<WithdrawalProduct> products =
        rows.stream().flatMap(row -> row.products().stream()).toList();
    BigDecimal remaining =
        unknownRemaining
            ? null
            : rows.stream()
                .map(row -> new BigDecimal(row.remainingSnapshot()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new WithdrawalOrder(
        rows.getFirst().id(),
        rows.getFirst().orderNo(),
        products,
        money(occupied),
        remaining == null ? null : money(remaining));
  }

  @Transactional(rollbackFor = Exception.class)
  public Withdrawal apply(KolAccountDTO kol, Request request) {
    if (request == null) {
      fail();
    }
    BigDecimal amount = parseMoney(request == null ? null : request.amount());
    if (amount.signum() <= 0) {
      fail();
    }
    UUID requestId = uuid(request.idempotencyKey());
    AccountSnapshot account = resolveAccount(kol.id(), request);
    String fingerprint =
        fingerprint(
            amount.toPlainString()
                + "|"
                + request.previousId()
                + "|"
                + account
                + "|"
                + trim(request.note(), 1000));
    List<Long> prior =
        jdbc.query(
            "SELECT id FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=? AND request_id=?",
            (rs, index) -> rs.getLong(1),
            kol.tenantId(),
            kol.id(),
            requestId);
    if (!prior.isEmpty()) {
      requireFingerprint(kol.tenantId(), prior.getFirst(), fingerprint);
      return detail(kol.tenantId(), kol.id(), prior.getFirst(), true, false);
    }
    settlement.releaseMatured(kol.tenantId(), kol.id());
    lockWallet(kol.tenantId(), kol.id());
    List<Long> concurrentPrior =
        jdbc.query(
            "SELECT id FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=? AND request_id=?",
            (rs, index) -> rs.getLong(1),
            kol.tenantId(),
            kol.id(),
            requestId);
    if (!concurrentPrior.isEmpty()) {
      requireFingerprint(kol.tenantId(), concurrentPrior.getFirst(), fingerprint);
      return detail(kol.tenantId(), kol.id(), concurrentPrior.getFirst(), true, false);
    }
    if (request.previousId() != null) {
      List<String> previous =
          jdbc.query(
              "SELECT status FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=? AND id=?",
              (rs, index) -> rs.getString(1),
              kol.tenantId(),
              kol.id(),
              request.previousId());
      if (previous.isEmpty() || !"REJECTED".equals(previous.getFirst())) {
        fail();
      }
    }
    BigDecimal minimum = minimum(kol.tenantId());
    BigDecimal available =
        wallet
            .currentBalance(kol.tenantId(), kol.id())
            .subtract(activeOccupied(kol.tenantId(), kol.id()));
    if (amount.compareTo(minimum) < 0 || amount.compareTo(available) > 0) {
      fail();
    }
    List<Allocatable> rows = allocatable(kol.tenantId(), kol.id());
    BigDecimal remaining = amount;
    long withdrawalId = IdWorker.getId();
    List<PendingAllocation> allocations = new ArrayList<>();
    for (Allocatable row : rows) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal take = remaining.min(row.remaining());
      if (take.signum() <= 0) {
        continue;
      }
      remaining = remaining.subtract(take);
      allocations.add(new PendingAllocation(row, take, row.remaining().subtract(take)));
    }
    if (remaining.signum() > 0) {
      fail();
    }
    Instant now = now();
    String no = "CW" + LocalDate.ofInstant(now, BEIJING).toString().replace("-", "") + withdrawalId;
    jdbc.update(
        "INSERT INTO crm_commission_withdrawal (id,tenant_id,kol_id,application_no,request_id,request_fingerprint,previous_id,amount_cny,status,account_id,account_type,account_cipher,account_masked,account_currency,account_network,account_holder,account_qr_file_id,note,actor_id,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,?,?,?,?,?,?,?,?,?)",
        withdrawalId,
        kol.tenantId(),
        kol.id(),
        no,
        requestId,
        fingerprint,
        request.previousId(),
        amount,
        account.id() == null ? null : Long.valueOf(account.id()),
        account.type(),
        cipher.encrypt(account.account()),
        AccountMasker.mask(account.account()),
        account.currency(),
        account.network(),
        account.holder(),
        account.qrFileId() == null ? null : Long.valueOf(account.qrFileId()),
        trim(request.note(), 1000),
        kol.id(),
        Timestamp.from(now),
        Timestamp.from(now));
    for (PendingAllocation allocation : allocations) {
      jdbc.update(
          "INSERT INTO crm_commission_withdrawal_allocation (id,tenant_id,withdrawal_id,item_state_id,order_item_id,occupied_cny,remaining_snapshot_cny,create_time) VALUES (?,?,?,?,?,?,?,?)",
          IdWorker.getId(),
          kol.tenantId(),
          withdrawalId,
          allocation.row().stateId(),
          allocation.row().itemId(),
          allocation.amount(),
          allocation.remainingSnapshot(),
          Timestamp.from(now));
    }
    if (request.payoutAccountId() == null && account.qrFileId() != null) {
      files.claimQrFile(kol.id(), Long.valueOf(account.qrFileId()), withdrawalId);
    }
    history(kol.tenantId(), withdrawalId, "SUBMITTED", kol.id(), trim(request.note(), 1000), now);
    return detail(kol.tenantId(), kol.id(), withdrawalId, true, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public Withdrawal withdraw(KolAccountDTO kol, long id) {
    WithdrawalRow row = lockWithdrawal(kol.tenantId(), id);
    if (row == null || row.kolId() != kol.id() || !"PENDING_REVIEW".equals(row.status())) {
      fail();
    }
    jdbc.update(
        "UPDATE crm_commission_withdrawal SET status='WITHDRAWN',update_time=? WHERE tenant_id=? AND id=? AND status='PENDING_REVIEW'",
        Timestamp.from(now()),
        kol.tenantId(),
        id);
    history(kol.tenantId(), id, "WITHDRAWN", kol.id(), null, now());
    return detail(kol.tenantId(), kol.id(), id, true, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public Withdrawal review(AccountDTO admin, long id, String decision, String note) {
    long tenantId = requireTenant(admin);
    WithdrawalRow row = lockWithdrawal(tenantId, id);
    if (row == null
        || !("PENDING_REVIEW".equals(row.status()) || "PENDING_PAYMENT".equals(row.status()))) {
      fail();
    }
    assertInScope(admin, row.kolId());
    if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
      fail();
    }
    if ("PENDING_PAYMENT".equals(row.status())
        && "REJECT".equals(decision)
        && admin.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN
        && admin.postTemplate() != com.tcrm.admin.security.api.PostTemplate.FINANCE
        && !admin.permissions().contains("prepay:pay")) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    String status = "APPROVE".equals(decision) ? "PENDING_PAYMENT" : "REJECTED";
    if ("APPROVE".equals(decision) && "PENDING_PAYMENT".equals(row.status())) {
      fail();
    }
    jdbc.update(
        "UPDATE crm_commission_withdrawal SET status=?,review_note=?,reviewer_id=?,reviewed_at=?,update_time=? WHERE tenant_id=? AND id=?",
        status,
        trim(note, 1000),
        admin.id(),
        Timestamp.from(now()),
        Timestamp.from(now()),
        tenantId,
        id);
    history(
        tenantId,
        id,
        "APPROVE".equals(decision) ? "APPROVED" : "REJECTED",
        admin.id(),
        trim(note, 1000),
        now());
    return detail(tenantId, null, id, false, canRevealAccount(admin));
  }

  @Transactional(rollbackFor = Exception.class)
  public Withdrawal registerPayment(AccountDTO admin, long id, PaymentRequest p) {
    if (p == null) {
      fail();
    }
    if (admin.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    long tenantId = requireTenant(admin);
    UUID requestId = uuid(p.idempotencyKey());
    BigDecimal actual = parsePositiveDecimal(p.actualAmount(), 2);
    BigDecimal amountCny = parseMoney(p.amountCny());
    if (amountCny.signum() <= 0
        || p.paidAt() == null
        || p.method() == null
        || p.method().isBlank()
        || p.voucher() == null) {
      fail();
    }
    String actualCurrency = p.actualCurrency();
    if (actualCurrency == null || !actualCurrency.matches("[A-Z]{3,8}")) {
      fail();
    }
    String fp =
        fingerprint(
            id
                + "|"
                + actual.toPlainString()
                + "|"
                + actualCurrency
                + "|"
                + amountCny.toPlainString()
                + "|"
                + p.paidAt()
                + "|"
                + p.method()
                + "|"
                + p.voucher());
    List<String> existing =
        jdbc.query(
            "SELECT request_fingerprint FROM crm_commission_withdrawal_payment WHERE tenant_id=? AND request_id=?",
            (rs, index) -> rs.getString(1),
            tenantId,
            requestId);
    if (!existing.isEmpty()) {
      if (!existing.getFirst().equals(fp)) {
        throw new BusinessException(ErrorCode.COMMON_CONFLICT);
      }
      return detail(tenantId, null, id, false, canRevealAccount(admin));
    }
    WithdrawalRow row = lockWithdrawal(tenantId, id);
    if (row == null || !"PENDING_PAYMENT".equals(row.status()) || row.paused()) {
      fail();
    }
    List<String> retry =
        jdbc.query(
            "SELECT request_fingerprint FROM crm_commission_withdrawal_payment WHERE tenant_id=? AND withdrawal_id=?",
            (rs, index) -> rs.getString(1),
            tenantId,
            id);
    if (!retry.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    if (amountCny.compareTo(row.amount()) != 0
        || !actualCurrency.equals(row.accountCurrency() == null ? "USDT" : row.accountCurrency())) {
      fail();
    }
    if (!"CNY".equals(actualCurrency)
        && (p.conversionNote() == null || p.conversionNote().isBlank())) {
      fail();
    }
    if ("CNY".equals(actualCurrency) && actual.compareTo(amountCny) != 0) {
      fail();
    }
    if (p.voucher() <= 0) {
      fail();
    }
    lockWallet(tenantId, row.kolId());
    BigDecimal occupied = allocationTotal(tenantId, id);
    if (occupied.compareTo(row.amount()) != 0) {
      fail();
    }
    files.claimWithdrawalVoucher(tenantId, admin.id(), p.voucher(), id);
    long paymentId = IdWorker.getId();
    Instant now = now();
    jdbc.update(
        "INSERT INTO crm_commission_withdrawal_payment (id,tenant_id,withdrawal_id,request_id,request_fingerprint,amount_cny,actual_amount,actual_currency,paid_at,method,conversion_note,finance_note,voucher_file_id,operator_id,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        paymentId,
        tenantId,
        id,
        requestId,
        fp,
        amountCny,
        actual,
        actualCurrency,
        Timestamp.from(p.paidAt()),
        trim(p.method(), 64),
        trim(p.conversionNote(), 1000),
        trim(p.financeNote(), 1000),
        p.voucher(),
        admin.id(),
        Timestamp.from(now));
    wallet.postSignedEntry(
        tenantId,
        row.kolId(),
        id,
        "WITHDRAWAL:" + id,
        "COMMISSION_WITHDRAWAL",
        amountCny.negate(),
        p.paidAt(),
        admin.id());
    jdbc.update(
        "UPDATE crm_commission_withdrawal SET status='PAID',finance_note=?,update_time=? WHERE tenant_id=? AND id=? AND status='PENDING_PAYMENT' AND paused=FALSE",
        trim(p.financeNote(), 1000),
        Timestamp.from(now),
        tenantId,
        id);
    history(tenantId, id, "PAID", admin.id(), null, now);
    return detail(tenantId, null, id, false, canRevealAccount(admin));
  }

  @Transactional(readOnly = true)
  public Withdrawal detail(
      long tenantId, Long kolId, long id, boolean owner, boolean revealAccount) {
    List<WithdrawalRow> rows =
        jdbc.query(
            "SELECT w.*,k.name kol_name,k.kol_code FROM crm_commission_withdrawal w JOIN crm_kol k ON k.id=w.kol_id AND k.tenant_id=w.tenant_id WHERE w.tenant_id=? AND w.id=?"
                + (kolId == null ? "" : " AND w.kol_id=?"),
            (rs, index) -> mapWithdrawal(rs),
            kolId == null ? new Object[] {tenantId, id} : new Object[] {tenantId, id, kolId});
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    WithdrawalRow r = rows.getFirst();
    AccountSnapshot account =
        new AccountSnapshot(
            r.accountId() == null ? null : r.accountId().toString(),
            r.accountType(),
            revealAccount ? cipher.decrypt(r.accountCipher()) : r.accountMasked(),
            r.accountCurrency(),
            r.accountNetwork(),
            r.accountHolder(),
            r.accountQrFileId() == null ? null : r.accountQrFileId().toString());
    List<Allocation> alloc =
        jdbc.query(
            "SELECT a.id,o.weidian_order_id,i.item_name,i.sku_name,a.occupied_cny,a.remaining_snapshot_cny FROM crm_commission_withdrawal_allocation a JOIN crm_order_item i ON i.id=a.order_item_id AND i.tenant_id=a.tenant_id JOIN crm_order o ON o.id=i.order_id AND o.tenant_id=i.tenant_id WHERE a.tenant_id=? AND a.withdrawal_id=? ORDER BY a.id",
            (rs, index) ->
                new Allocation(
                    Long.toString(rs.getLong(1)),
                    rs.getString(2),
                    null,
                    rs.getString(3),
                    rs.getString(4),
                    money(rs.getBigDecimal(5)),
                    money(rs.getBigDecimal(6))),
            tenantId,
            id);
    List<History> history =
        jdbc.query(
            "SELECT h.action,h.create_time,u.nickname,h.note FROM crm_commission_withdrawal_history h LEFT JOIN sys_user u ON u.id=h.actor_id WHERE h.tenant_id=? AND h.withdrawal_id=? ORDER BY h.create_time,h.id",
            (rs, index) ->
                new History(
                    rs.getString(1),
                    rs.getTimestamp(2).toInstant(),
                    rs.getString(3),
                    owner ? rs.getString(4) : null),
            tenantId,
            id);
    List<Payment> payments =
        jdbc.query(
            "SELECT id,actual_amount,actual_currency,amount_cny,paid_at,method,conversion_note,finance_note,voucher_file_id FROM crm_commission_withdrawal_payment WHERE tenant_id=? AND withdrawal_id=?",
            (rs, index) ->
                new Payment(
                    rs.getBigDecimal("actual_amount").stripTrailingZeros().toPlainString(),
                    rs.getString("actual_currency"),
                    money(rs.getBigDecimal("amount_cny")),
                    rs.getTimestamp("paid_at").toInstant(),
                    rs.getString("method"),
                    rs.getString("conversion_note"),
                    owner ? null : rs.getString("finance_note"),
                    Long.toString(rs.getLong("voucher_file_id")),
                    Long.toString(id)),
            tenantId,
            id);
    return new Withdrawal(
        Long.toString(id),
        r.applicationNo(),
        Long.toString(r.kolId()),
        r.kolName(),
        r.kolCode(),
        "COMMISSION",
        money(r.amount()),
        "CNY",
        r.status(),
        r.createdAt(),
        r.paused(),
        r.pauseReason(),
        account,
        r.note(),
        owner ? r.reviewNote() : r.reviewNote(),
        owner ? null : r.financeNote(),
        alloc,
        history,
        payments.isEmpty() ? null : payments.getFirst(),
        r.previousId() == null ? null : r.previousId().toString());
  }

  @Transactional(readOnly = true)
  public Withdrawal detailAdmin(AccountDTO admin, long id) {
    long tenantId = requireTenant(admin);
    if (admin.ownScope()) {
      List<Long> target =
          jdbc.query(
              "SELECT kol_id FROM crm_commission_withdrawal WHERE tenant_id=? AND id=?",
              (rs, index) -> rs.getLong(1),
              tenantId,
              id);
      boolean inScope =
          !target.isEmpty()
              && kols.listByBdUserId(admin.id()).stream()
                  .anyMatch(k -> k.id().equals(target.getFirst()));
      if (!inScope) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
    }
    return detail(tenantId, null, id, false, canRevealAccount(admin));
  }

  @Transactional(readOnly = true)
  public PageResult<Withdrawal> pageKol(KolAccountDTO kol, int page, int size) {
    validatePage(page, size);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=?",
            Long.class,
            kol.tenantId(),
            kol.id());
    List<Long> ids =
        jdbc.query(
            "SELECT id FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=? ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
            (rs, index) -> rs.getLong(1),
            kol.tenantId(),
            kol.id(),
            size,
            (long) (page - 1) * size);
    return new PageResult<>(
        ids.stream().map(id -> detail(kol.tenantId(), kol.id(), id, true, false)).toList(),
        total == null ? 0L : total,
        page,
        size);
  }

  @Transactional(readOnly = true)
  public PageResult<Withdrawal> pageAdmin(AccountDTO admin, Filter filter) {
    long tenantId = requireTenant(admin);
    validatePage(filter.page(), filter.size());
    StringBuilder where = new StringBuilder(" WHERE w.tenant_id=?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (filter.applicationNo() != null && !filter.applicationNo().isBlank()) {
      where.append(" AND w.application_no=?");
      args.add(filter.applicationNo().strip());
    }
    if (filter.status() != null && !filter.status().isBlank()) {
      where.append(" AND w.status=?");
      args.add(filter.status());
    }
    if (filter.kol() != null && !filter.kol().isBlank()) {
      where.append(" AND (k.kol_code ILIKE ? OR k.name ILIKE ?)");
      args.add("%" + filter.kol().strip() + "%");
      args.add("%" + filter.kol().strip() + "%");
    }
    if (admin.ownScope()) {
      List<Long> visible =
          kols.listByBdUserId(admin.id()).stream().map(KolRefDTO::id).distinct().toList();
      if (visible.isEmpty()) {
        return new PageResult<>(List.of(), 0L, filter.page(), filter.size());
      }
      where
          .append(" AND w.kol_id IN (")
          .append(String.join(",", java.util.Collections.nCopies(visible.size(), "?")))
          .append(")");
      args.addAll(visible);
    }
    String from =
        " FROM crm_commission_withdrawal w JOIN crm_kol k ON k.id=w.kol_id AND k.tenant_id=w.tenant_id";
    Long total = jdbc.queryForObject("SELECT count(*)" + from + where, args.toArray(), Long.class);
    args.add(filter.size());
    args.add((long) (filter.page() - 1) * filter.size());
    List<Long> ids =
        jdbc.query(
            "SELECT w.id"
                + from
                + where
                + " ORDER BY w.create_time DESC,w.id DESC LIMIT ? OFFSET ?",
            (rs, index) -> rs.getLong(1),
            args.toArray());
    return new PageResult<>(
        ids.stream().map(id -> detail(tenantId, null, id, false, false)).toList(),
        total == null ? 0L : total,
        filter.page(),
        filter.size());
  }

  @Transactional(readOnly = true)
  public BizFileService.FileDTO kolVoucher(KolAccountDTO kol, long fileId) {
    BizFileService.AttachedFile attached = files.attachedFile(fileId);
    if (attached.bizType() != FileBizType.WITHDRAWAL_VOUCHER || attached.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    List<Long> match =
        jdbc.query(
            "SELECT p.withdrawal_id FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE p.tenant_id=? AND p.withdrawal_id=? AND w.kol_id=? AND w.status='PAID' AND p.voucher_file_id=?",
            (rs, index) -> rs.getLong(1),
            kol.tenantId(),
            attached.bizId(),
            kol.id(),
            fileId);
    if (match.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return attached.download();
  }

  @Transactional(readOnly = true)
  public BizFileService.FileDTO adminVoucher(AccountDTO admin, long fileId) {
    if (admin.role() != com.tcrm.admin.security.api.Role.SUPER_ADMIN) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    long tenant = requireTenant(admin);
    BizFileService.AttachedFile attached = files.attachedFile(fileId);
    if (attached.bizType() != FileBizType.WITHDRAWAL_VOUCHER || attached.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    List<Long> rows =
        jdbc.query(
            "SELECT p.id FROM crm_commission_withdrawal_payment p JOIN crm_commission_withdrawal w ON w.id=p.withdrawal_id AND w.tenant_id=p.tenant_id WHERE p.tenant_id=? AND p.withdrawal_id=? AND p.voucher_file_id=? AND w.status='PAID'",
            (rs, index) -> rs.getLong(1),
            tenant,
            attached.bizId(),
            fileId);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return attached.download();
  }

  private AccountSnapshot resolveAccount(long kolId, Request request) {
    if (request.payoutAccountId() != null) {
      PayoutAccountService.KolAccountDTO found =
          payoutAccounts.listMine(kolId).stream()
              .filter(a -> a.id().equals(request.payoutAccountId()))
              .findFirst()
              .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND));
      if (found.status() != PayoutAccountStatus.APPROVED) {
        fail();
      }
      return new AccountSnapshot(
          found.id().toString(),
          found.accountType().name(),
          found.account(),
          found.currency(),
          found.network() == null ? null : found.network().name(),
          found.holderName(),
          found.qrFileId() == null ? null : found.qrFileId().toString());
    }
    AccountInput a = request.account();
    if (a == null
        || a.type() == null
        || a.account() == null
        || a.holder() == null
        || a.holder().isBlank()) {
      fail();
    }
    String account = a.account().strip();
    String holder = a.holder().strip();
    if ("PAYPAL".equals(a.type())) {
      if (!account.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
          || !PAYPAL_CURRENCIES.contains(a.currency())
          || a.network() != null) {
        fail();
      }
      return new AccountSnapshot(
          null,
          "PAYPAL",
          account,
          a.currency(),
          null,
          holder,
          a.qrFileId() == null ? null : a.qrFileId().toString());
    }
    if ("USDT".equals(a.type())) {
      if (!account.matches("^[A-Za-z0-9]{3,128}$")
          || !List.of(UsdtNetwork.values()).contains(network(a.network()))
          || a.currency() != null) {
        fail();
      }
      return new AccountSnapshot(
          null,
          "USDT",
          account,
          null,
          a.network(),
          holder,
          a.qrFileId() == null ? null : a.qrFileId().toString());
    }
    fail();
    return null;
  }

  private List<Allocatable> allocatable(long tenantId, long kolId) {
    return jdbc.query(
        "SELECT s.id,s.order_item_id,s.released_commission_cny-COALESCE(SUM(CASE WHEN w.status IN ('PENDING_REVIEW','PENDING_PAYMENT','PAID') THEN a.occupied_cny ELSE 0 END),0) remaining FROM crm_commission_item_state s LEFT JOIN crm_commission_withdrawal_allocation a ON a.tenant_id=s.tenant_id AND a.item_state_id=s.id LEFT JOIN crm_commission_withdrawal w ON w.tenant_id=a.tenant_id AND w.id=a.withdrawal_id WHERE s.tenant_id=? AND s.kol_id=? AND s.verified=TRUE AND s.after_sales=FALSE AND s.release_basis IN ('CONFIRMED','FALLBACK') AND s.release_at<=? GROUP BY s.id,s.order_item_id,s.released_commission_cny HAVING s.released_commission_cny>COALESCE(SUM(CASE WHEN w.status IN ('PENDING_REVIEW','PENDING_PAYMENT','PAID') THEN a.occupied_cny ELSE 0 END),0) ORDER BY s.release_at,s.order_item_id FOR UPDATE OF s",
        (rs, index) -> new Allocatable(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)),
        tenantId,
        kolId,
        Timestamp.from(clock.instant()));
  }

  private BigDecimal activeOccupied(long tenantId, long kolId) {
    return nvl(
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount_cny),0) FROM crm_commission_withdrawal WHERE tenant_id=? AND kol_id=? AND status IN ('PENDING_REVIEW','PENDING_PAYMENT')",
            BigDecimal.class,
            tenantId,
            kolId));
  }

  private BigDecimal allocationTotal(long tenantId, long id) {
    return nvl(
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(occupied_cny),0) FROM crm_commission_withdrawal_allocation WHERE tenant_id=? AND withdrawal_id=?",
            BigDecimal.class,
            tenantId,
            id));
  }

  private BigDecimal minimum(long tenantId) {
    return new BigDecimal(settlement.currentConfig(tenantId).minimum());
  }

  private void lockWallet(long tenantId, long kolId) {
    Instant now = now();
    jdbc.update(
        "INSERT INTO crm_commission_wallet(id,tenant_id,kol_id,balance_cny,create_time,update_time) VALUES(?,?,?,0,?,?) ON CONFLICT(tenant_id,kol_id) DO NOTHING",
        IdWorker.getId(),
        tenantId,
        kolId,
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.queryForObject(
        "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id=? AND kol_id=? FOR UPDATE",
        BigDecimal.class,
        tenantId,
        kolId);
  }

  private WithdrawalRow lockWithdrawal(long tenantId, long id) {
    List<WithdrawalRow> rows =
        jdbc.query(
            "SELECT w.*,k.name kol_name,k.kol_code FROM crm_commission_withdrawal w JOIN crm_kol k ON k.id=w.kol_id AND k.tenant_id=w.tenant_id WHERE w.tenant_id=? AND w.id=? FOR UPDATE OF w",
            (rs, index) -> mapWithdrawal(rs),
            tenantId,
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private WithdrawalRow mapWithdrawal(ResultSet rs) throws SQLException {
    return new WithdrawalRow(
        rs.getLong("id"),
        rs.getLong("kol_id"),
        rs.getString("application_no"),
        rs.getString("status"),
        rs.getBigDecimal("amount_cny"),
        rs.getTimestamp("create_time").toInstant(),
        rs.getBoolean("paused"),
        rs.getString("pause_reason"),
        nullableLong(rs, "account_id"),
        rs.getString("account_type"),
        rs.getString("account_cipher"),
        rs.getString("account_masked"),
        rs.getString("account_currency"),
        rs.getString("account_network"),
        rs.getString("account_holder"),
        nullableLong(rs, "account_qr_file_id"),
        rs.getString("note"),
        rs.getString("review_note"),
        rs.getString("finance_note"),
        rs.getObject("previous_id", Long.class),
        rs.getString("request_fingerprint"),
        rs.getString("kol_name"),
        rs.getString("kol_code"));
  }

  private void requireFingerprint(long tenantId, long id, String fp) {
    String existing =
        jdbc.queryForObject(
            "SELECT request_fingerprint FROM crm_commission_withdrawal WHERE tenant_id=? AND id=?",
            String.class,
            tenantId,
            id);
    if (!fp.equals(existing)) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
  }

  private void history(long tenantId, long id, String action, long actor, String note, Instant at) {
    jdbc.update(
        "INSERT INTO crm_commission_withdrawal_history(id,tenant_id,withdrawal_id,action,actor_id,note,create_time) VALUES(?,?,?,?,?,?,?)",
        IdWorker.getId(),
        tenantId,
        id,
        action,
        actor,
        trim(note, 1000),
        Timestamp.from(at));
  }

  private static long requireTenant(AccountDTO admin) {
    if (admin.tenantId() == null || admin.tenantId() <= 0) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return admin.tenantId();
  }

  private void assertInScope(AccountDTO admin, long kolId) {
    if (admin.ownScope()
        && kols.listByBdUserId(admin.id()).stream().noneMatch(k -> k.id() == kolId)) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
  }

  private static boolean canRevealAccount(AccountDTO admin) {
    return admin.role() == com.tcrm.admin.security.api.Role.SUPER_ADMIN;
  }

  private static UsdtNetwork network(String value) {
    try {
      return UsdtNetwork.valueOf(value);
    } catch (Exception e) {
      return null;
    }
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      fail();
      return null;
    }
  }

  private static BigDecimal parseMoney(String value) {
    BigDecimal result = parsePositiveDecimal(value, 2);
    return result.setScale(2, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal parsePositiveDecimal(String value, int maxScale) {
    try {
      BigDecimal b = new BigDecimal(value);
      if (b.signum() <= 0 || b.scale() > maxScale) {
        fail();
      }
      return b;
    } catch (Exception e) {
      fail();
      return BigDecimal.ZERO;
    }
  }

  private static String fingerprint(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void validatePage(int page, int size) {
    if (page < 1 || size < 1 || size > 100) {
      fail();
    }
  }

  private static void fail() {
    throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  private static BigDecimal nvl(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static String trim(String s, int max) {
    if (s == null) {
      return null;
    }
    s = s.strip();
    return s.length() <= max ? s : s.substring(0, max);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private record Allocatable(long stateId, long itemId, BigDecimal remaining) {}

  private record PendingAllocation(
      Allocatable row, BigDecimal amount, BigDecimal remainingSnapshot) {}

  private record WithdrawalRow(
      long id,
      long kolId,
      String applicationNo,
      String status,
      BigDecimal amount,
      Instant createdAt,
      boolean paused,
      String pauseReason,
      Long accountId,
      String accountType,
      String accountCipher,
      String accountMasked,
      String accountCurrency,
      String accountNetwork,
      String accountHolder,
      Long accountQrFileId,
      String note,
      String reviewNote,
      String financeNote,
      Long previousId,
      String requestFingerprint,
      String kolName,
      String kolCode) {}
}
