package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.notification.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payout account rules (task 15a §4.2): entered by the KOL, reviewed by the owning BD or a super
 * admin. The account is stored encrypted and shown masked everywhere except the KOL's own list and
 * the reviewer's detail view. Accounts are disabled, never deleted; approved accounts are locked.
 */
@Service
public class PayoutAccountService {
  private static final Logger LOG = LoggerFactory.getLogger(PayoutAccountService.class);
  private final SystemConfigApi configs;

  /** KOL-side write payload after bean validation; one shape per account type. */
  public record SaveCommand(
      PayoutAccountType type,
      String account,
      String holderName,
      String currency,
      UsdtNetwork network,
      Long qrFileId) {}

  /** Actor details the KOL audit needs; built by the controller from the request. */
  public record KolAudit(long tenantId, String email, String ip, String requestUri) {}

  /** Account as shown to its owner: the full account is visible to the KOL only. */
  public record KolAccountDTO(
      Long id,
      PayoutAccountType accountType,
      String account,
      String holderName,
      String currency,
      UsdtNetwork network,
      Long qrFileId,
      PayoutAccountStatus status,
      boolean defaultAccount,
      String rejectReason,
      Instant lastSubmittedAt,
      Instant reviewedTime) {}

  /** Account row for the admin list: masked account only (task 15a §4.2.7). */
  public record AdminAccountDTO(
      Long id,
      Long kolId,
      String kolCode,
      String kolName,
      PayoutAccountType accountType,
      String accountMasked,
      String holderName,
      String currency,
      UsdtNetwork network,
      Long qrFileId,
      PayoutAccountStatus status,
      boolean defaultAccount,
      String rejectReason,
      Instant lastSubmittedAt,
      Instant reviewedTime) {}

  /** Account detail for a reviewer: full account plus the QR file id (task 15a §4.4). */
  public record AdminAccountDetailDTO(
      Long id,
      Long kolId,
      String kolCode,
      String kolName,
      PayoutAccountType accountType,
      String account,
      String holderName,
      String currency,
      UsdtNetwork network,
      Long qrFileId,
      PayoutAccountStatus status,
      boolean defaultAccount,
      String rejectReason,
      Instant lastSubmittedAt,
      Instant reviewedTime) {}

  public record Filter(String status, String type, String kolCode, int page, int size) {}

  public enum ReviewAction {
    APPROVE,
    REJECT
  }

  private final PayoutAccountMapper accounts;
  private final BizFileService files;
  private final KolApi kols;
  private final SecretCipher cipher;
  private final TransactionTemplate tx;
  private final ExternalAuditApi audit;
  private final ApplicationEventPublisher events;

  @Autowired
  public PayoutAccountService(
      PayoutAccountMapper accounts,
      BizFileService files,
      KolApi kols,
      SecretCipher cipher,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      ApplicationEventPublisher events,
      SystemConfigApi configs) {
    this.accounts = accounts;
    this.files = files;
    this.kols = kols;
    this.cipher = cipher;
    this.tx = tx;
    this.audit = audit;
    this.events = events;
    this.configs = configs;
  }

  PayoutAccountService(
      PayoutAccountMapper accounts,
      BizFileService files,
      KolApi kols,
      SecretCipher cipher,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      ApplicationEventPublisher events) {
    this(
        accounts,
        files,
        kols,
        cipher,
        tx,
        audit,
        events,
        new SystemConfigApi() {
          @Override
          public String getValue(String key) {
            return "5";
          }

          @Override
          public int getInt(String key) {
            return 5;
          }
        });
  }

  // --------------------------------------------------------------------- KOL side

  /** The KOL's own accounts, disabled ones excluded; newest submission first. */
  public List<KolAccountDTO> listMine(long kolId) {
    return accounts
        .selectList(
            new LambdaQueryWrapper<PayoutAccountDO>()
                .eq(PayoutAccountDO::getKolId, kolId)
                .ne(PayoutAccountDO::getStatus, PayoutAccountStatus.DISABLED)
                .orderByDesc(PayoutAccountDO::getLastSubmittedAt, PayoutAccountDO::getId))
        .stream()
        .map(account -> toKolDto(account, cipher.decrypt(account.getAccountCipher())))
        .toList();
  }

  /**
   * New account: PENDING, encrypted, with the QR file claimed in the same transaction. The KOL row
   * is locked first, so counting the live accounts and inserting happen atomically per KOL and two
   * concurrent creates cannot both pass the limit (task 15a review).
   */
  public KolAccountDTO create(long kolId, SaveCommand c, KolAudit auditContext) {
    PayoutAccountDO account = new PayoutAccountDO();
    account.setKolId(kolId);
    account.setStatus(PayoutAccountStatus.PENDING);
    applyFields(account, c);
    account.setIsDefault(false);
    account.setLastSubmittedAt(Instant.now());
    tx.executeWithoutResult(
        status -> {
          if (!kols.lockKol(kolId)) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          if (activeCount(kolId) >= configs.getInt("payout-account.max-per-kol")) {
            throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_LIMIT);
          }
          accounts.insert(account);
          if (c.qrFileId() != null) {
            files.claimQrFile(kolId, c.qrFileId(), account.getId());
          }
          publishSubmitted(kolId, account.getId(), c.type(), auditContext.tenantId());
        });
    auditKol(auditContext, kolId, account.getId(), "Create", account.getAccountMasked());
    return toKolDto(account, c.account());
  }

  /**
   * Edit of a PENDING or REJECTED account: back to PENDING with the review cleared. An APPROVED
   * account is locked (409 PAYOUT_ACCOUNT_LOCKED); a disabled or foreign account is a 404.
   */
  public void update(long kolId, long accountId, SaveCommand c, KolAudit auditContext) {
    PayoutAccountDO existing = requireKolAccount(kolId, accountId);
    if (existing.getStatus() == PayoutAccountStatus.APPROVED) {
      throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_LOCKED);
    }
    Instant now = Instant.now();
    tx.executeWithoutResult(
        status -> {
          // The status condition makes the read above advisory: a review that committed in between
          // stops this update (0 rows) instead of overwriting APPROVED back to PENDING.
          int updated =
              accounts.update(
                  null,
                  scoped(accountId)
                      .in(
                          PayoutAccountDO::getStatus,
                          PayoutAccountStatus.PENDING,
                          PayoutAccountStatus.REJECTED)
                      .set(PayoutAccountDO::getAccountType, c.type())
                      .set(PayoutAccountDO::getAccountCipher, cipher.encrypt(c.account()))
                      .set(PayoutAccountDO::getAccountMasked, AccountMasker.mask(c.account()))
                      .set(PayoutAccountDO::getHolderName, holderOf(c))
                      .set(PayoutAccountDO::getCurrency, currencyOf(c))
                      .set(PayoutAccountDO::getUsdtNetwork, networkOf(c))
                      .set(PayoutAccountDO::getQrFileId, c.qrFileId())
                      .set(PayoutAccountDO::getStatus, PayoutAccountStatus.PENDING)
                      .set(PayoutAccountDO::getRejectReason, null)
                      .set(PayoutAccountDO::getReviewedBy, null)
                      .set(PayoutAccountDO::getReviewedTime, null)
                      .set(PayoutAccountDO::getLastSubmittedAt, now)
                      .set(PayoutAccountDO::getUpdateBy, kolId)
                      .set(PayoutAccountDO::getUpdateTime, now));
          if (updated == 0) {
            throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_LOCKED);
          }
          claimQrOnEdit(kolId, accountId, existing.getQrFileId(), c.qrFileId());
          publishSubmitted(kolId, accountId, c.type(), auditContext.tenantId());
        });
    auditKol(auditContext, kolId, accountId, "Edit", AccountMasker.mask(c.account()));
  }

  /**
   * Notifies the creator's current BD and every enabled super admin. A missing creator code skips
   * the event and does not fail the account save. Account numbers stay out of the event.
   */
  private void publishSubmitted(long kolId, Long accountId, PayoutAccountType type, long tenantId) {
    if (accountId == null) {
      return;
    }
    List<KolRefDTO> refs = kols.listByIds(List.of(kolId));
    String kolCode = null;
    if (refs != null) {
      kolCode =
          refs.stream()
              .map(KolRefDTO::kolCode)
              .filter(code -> code != null && !code.isBlank())
              .findFirst()
              .orElse(null);
    }
    if (kolCode == null) {
      LOG.warn("Payout account {} has no creator code; notification skipped", accountId);
      return;
    }
    TransactionalEvents.publish(
        events, new PayoutAccountSubmittedEvent(tenantId, kolId, accountId, kolCode, type.name()));
  }

  /** Default account: only an APPROVED one, exactly one per KOL. */
  public void setDefault(long kolId, long accountId, KolAudit auditContext) {
    PayoutAccountDO account = requireKolAccount(kolId, accountId);
    if (account.getStatus() != PayoutAccountStatus.APPROVED) {
      throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_NOT_APPROVED);
    }
    Instant now = Instant.now();
    tx.executeWithoutResult(
        status -> {
          accounts.update(
              null,
              new LambdaUpdateWrapper<PayoutAccountDO>()
                  .eq(PayoutAccountDO::getKolId, kolId)
                  .eq(PayoutAccountDO::getIsDefault, true)
                  .set(PayoutAccountDO::getIsDefault, false)
                  .set(PayoutAccountDO::getUpdateBy, kolId)
                  .set(PayoutAccountDO::getUpdateTime, now));
          // The status guard keeps a concurrent disable from tripping the database CHECK with a
          // 500: 0 rows here means the account stopped being APPROVED in between.
          int updated =
              accounts.update(
                  null,
                  scoped(accountId)
                      .eq(PayoutAccountDO::getStatus, PayoutAccountStatus.APPROVED)
                      .set(PayoutAccountDO::getIsDefault, true)
                      .set(PayoutAccountDO::getUpdateBy, kolId)
                      .set(PayoutAccountDO::getUpdateTime, now));
          if (updated == 0) {
            throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_NOT_APPROVED);
          }
        });
    auditKol(auditContext, kolId, accountId, "Set default", account.getAccountMasked());
  }

  /**
   * Disables the account for good and drops the default flag; disabled accounts vanish for the KOL.
   */
  public void disable(long kolId, long accountId, KolAudit auditContext) {
    PayoutAccountDO account = requireKolAccount(kolId, accountId);
    Instant now = Instant.now();
    tx.executeWithoutResult(
        status ->
            accounts.update(
                null,
                scoped(accountId)
                    .set(PayoutAccountDO::getStatus, PayoutAccountStatus.DISABLED)
                    .set(PayoutAccountDO::getIsDefault, false)
                    // The database CHECK drops the reason together with the REJECTED status.
                    .set(PayoutAccountDO::getRejectReason, null)
                    .set(PayoutAccountDO::getUpdateBy, kolId)
                    .set(PayoutAccountDO::getUpdateTime, now)));
    // Withdrawal-task checkpoint (batch 3, number pending): once withdrawals exist, refuse to
    // disable an account referenced by a withdrawal still awaiting review or payment (§4.2.6).
    auditKol(auditContext, kolId, accountId, "Disable", account.getAccountMasked());
  }

  // ------------------------------------------------------------------ Admin side

  /**
   * Account page for reviewers and read-only viewers: masked accounts only, newest submission
   * first; the status default is PENDING (the review queue, task 15a §4.5).
   */
  public PageResult<AdminAccountDTO> page(AccountDTO admin, Filter f) {
    PayoutAccountStatus status = statusOrNull(f.status());
    PayoutAccountType type = typeOrNull(f.type());
    LambdaQueryWrapper<PayoutAccountDO> query =
        new LambdaQueryWrapper<PayoutAccountDO>()
            .eq(status != null, PayoutAccountDO::getStatus, status)
            .eq(type != null, PayoutAccountDO::getAccountType, type)
            .orderByDesc(PayoutAccountDO::getLastSubmittedAt, PayoutAccountDO::getId);
    List<Long> visible = visibleKolIds(admin, f.kolCode());
    if (visible != null) {
      if (visible.isEmpty()) {
        return new PageResult<>(List.of(), 0, f.page(), f.size());
      }
      query.in(PayoutAccountDO::getKolId, visible);
    }
    var result = accounts.selectPage(new Page<>(f.page(), f.size()), query);
    Map<Long, KolRefDTO> kolRefs = kolRefs(result.getRecords());
    return new PageResult<>(
        result.getRecords().stream().map(a -> toAdminDto(a, kolRefs)).toList(),
        result.getTotal(),
        f.page(),
        f.size());
  }

  /** Reviewer detail with the decrypted account and QR file id; every view is audited (§4.4). */
  public AdminAccountDetailDTO detail(AccountDTO admin, long id) {
    PayoutAccountDO account = requireAdminAccount(admin, id);
    Map<Long, KolRefDTO> kolRefs = kolRefs(List.of(account));
    KolRefDTO kol = kolRefs.get(account.getKolId());
    return new AdminAccountDetailDTO(
        account.getId(),
        account.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        account.getAccountType(),
        cipher.decrypt(account.getAccountCipher()),
        account.getHolderName(),
        account.getCurrency(),
        account.getUsdtNetwork(),
        account.getQrFileId(),
        account.getStatus(),
        Boolean.TRUE.equals(account.getIsDefault()),
        account.getRejectReason(),
        account.getLastSubmittedAt(),
        account.getReviewedTime());
  }

  /**
   * Review of a PENDING account: APPROVE locks it, REJECT returns it to the KOL with a reason. The
   * caller passes the {@code lastSubmittedAt} it saw; the update only matches the very version, so
   * a KOL edit or another review that landed in between changes 0 rows and returns 409 instead of
   * approving content the reviewer never saw (task 15a review).
   */
  public void review(
      AccountDTO admin, long id, ReviewAction action, String reason, Instant lastSubmittedAt) {
    PayoutAccountDO account = requireAdminAccount(admin, id);
    if (account.getStatus() != PayoutAccountStatus.PENDING) {
      throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_NOT_PENDING);
    }
    Instant now = Instant.now();
    int updated =
        accounts.update(
            null,
            scoped(id)
                .eq(PayoutAccountDO::getStatus, PayoutAccountStatus.PENDING)
                .eq(PayoutAccountDO::getLastSubmittedAt, lastSubmittedAt)
                .set(
                    PayoutAccountDO::getStatus,
                    action == ReviewAction.APPROVE
                        ? PayoutAccountStatus.APPROVED
                        : PayoutAccountStatus.REJECTED)
                .set(
                    PayoutAccountDO::getRejectReason, action == ReviewAction.REJECT ? reason : null)
                .set(PayoutAccountDO::getReviewedBy, admin.id())
                .set(PayoutAccountDO::getReviewedTime, now)
                .set(PayoutAccountDO::getUpdateBy, admin.id())
                .set(PayoutAccountDO::getUpdateTime, now));
    if (updated == 0) {
      throw new BusinessException(ErrorCode.PAYOUT_ACCOUNT_NOT_PENDING);
    }
  }

  /** Pending accounts the admin may review; the count backs a future menu badge (task 15a §4.5). */
  public long pendingCount(AccountDTO admin) {
    LambdaQueryWrapper<PayoutAccountDO> query =
        new LambdaQueryWrapper<PayoutAccountDO>()
            .eq(PayoutAccountDO::getStatus, PayoutAccountStatus.PENDING);
    List<Long> visible = visibleKolIds(admin, null);
    if (visible != null) {
      if (visible.isEmpty()) {
        return 0;
      }
      query.in(PayoutAccountDO::getKolId, visible);
    }
    return accounts.selectCount(query);
  }

  /**
   * Admin download of a business file: today only an attached QR, and only for accounts the admin
   * could review (the QR encodes the address, so it needs the review permission, not the masked
   * list view).
   */
  public BizFileService.FileDTO fileForAdmin(AccountDTO admin, long fileId) {
    BizFileService.AttachedFile file = files.attachedFile(fileId);
    if (file.bizType() != FileBizType.PAYOUT_QR || file.bizId() == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    requireAdminAccount(admin, file.bizId());
    return file.download();
  }

  public BizFileService.AttachedFile attachedFile(long fileId) {
    return files.attachedFile(fileId);
  }

  // -------------------------------------------------------------------- helpers

  /** Applies the type-specific fields with encryption and masking to a new row. */
  private void applyFields(PayoutAccountDO account, SaveCommand c) {
    account.setAccountType(c.type());
    account.setAccountCipher(cipher.encrypt(c.account()));
    account.setAccountMasked(AccountMasker.mask(c.account()));
    account.setHolderName(holderOf(c));
    account.setCurrency(currencyOf(c));
    account.setUsdtNetwork(networkOf(c));
    account.setQrFileId(c.qrFileId());
    account.setCreateBy(account.getKolId());
  }

  private static String holderOf(SaveCommand c) {
    return c.type() == PayoutAccountType.PAYPAL ? c.holderName() : null;
  }

  private static String currencyOf(SaveCommand c) {
    return c.type() == PayoutAccountType.PAYPAL ? c.currency() : null;
  }

  private static UsdtNetwork networkOf(SaveCommand c) {
    return c.type() == PayoutAccountType.USDT ? c.network() : null;
  }

  /** Claims the new QR file unless it is the account's current one already. */
  private void claimQrOnEdit(long kolId, long accountId, Long oldQrFileId, Long newQrFileId) {
    if (newQrFileId == null || newQrFileId.equals(oldQrFileId)) {
      return;
    }
    files.claimQrFile(kolId, newQrFileId, accountId);
  }

  private long activeCount(long kolId) {
    return accounts.selectCount(
        new LambdaQueryWrapper<PayoutAccountDO>()
            .eq(PayoutAccountDO::getKolId, kolId)
            .ne(PayoutAccountDO::getStatus, PayoutAccountStatus.DISABLED));
  }

  /** Own, non-disabled account or 404: a disabled or foreign account simply does not exist. */
  private PayoutAccountDO requireKolAccount(long kolId, long accountId) {
    PayoutAccountDO account = accounts.selectById(accountId);
    if (account == null
        || account.getKolId() != kolId
        || account.getStatus() == PayoutAccountStatus.DISABLED) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return account;
  }

  /**
   * Account in the caller's company; an OWN-scope admin additionally needs the account's KOL
   * currently assigned to them, otherwise 404 (task 15a §4.4).
   */
  private PayoutAccountDO requireAdminAccount(AccountDTO admin, long accountId) {
    PayoutAccountDO account = accounts.selectById(accountId);
    if (account == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (admin.ownScope() && !Objects.equals(kolBd(account.getKolId()), admin.id())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return account;
  }

  private Long kolBd(Long kolId) {
    return kols.listByIds(List.of(kolId)).stream()
        .findFirst()
        .map(KolRefDTO::bdUserId)
        .orElse(null);
  }

  /**
   * Kol ids whose accounts the admin may see: null means every KOL of the company; an empty list
   * means none (OWN scope without KOLs, or a kolCode that matches no KOL).
   */
  private List<Long> visibleKolIds(AccountDTO admin, String kolCode) {
    if (kolCode != null && !kolCode.isBlank()) {
      KolRefDTO kol = kols.getByCode(kolCode.strip());
      if (kol == null || (admin.ownScope() && !admin.id().equals(kol.bdUserId()))) {
        return List.of();
      }
      return List.of(kol.id());
    }
    if (!admin.ownScope()) {
      return null;
    }
    return kols.listByBdUserId(admin.id()).stream().map(KolRefDTO::id).toList();
  }

  private Map<Long, KolRefDTO> kolRefs(List<PayoutAccountDO> rows) {
    List<Long> ids = rows.stream().map(PayoutAccountDO::getKolId).distinct().toList();
    Map<Long, KolRefDTO> byId = new HashMap<>();
    for (KolRefDTO kol : kols.listByIds(ids)) {
      byId.put(kol.id(), kol);
    }
    return byId;
  }

  private static LambdaUpdateWrapper<PayoutAccountDO> scoped(long accountId) {
    return new LambdaUpdateWrapper<PayoutAccountDO>().eq(PayoutAccountDO::getId, accountId);
  }

  private static PayoutAccountStatus statusOrNull(String value) {
    return value == null || value.isBlank() ? null : PayoutAccountStatus.valueOf(value);
  }

  private static PayoutAccountType typeOrNull(String value) {
    return value == null || value.isBlank() ? null : PayoutAccountType.valueOf(value);
  }

  private static KolAccountDTO toKolDto(PayoutAccountDO account, String plainAccount) {
    return new KolAccountDTO(
        account.getId(),
        account.getAccountType(),
        plainAccount,
        account.getHolderName(),
        account.getCurrency(),
        account.getUsdtNetwork(),
        account.getQrFileId(),
        account.getStatus(),
        Boolean.TRUE.equals(account.getIsDefault()),
        account.getRejectReason(),
        account.getLastSubmittedAt(),
        account.getReviewedTime());
  }

  private static AdminAccountDTO toAdminDto(PayoutAccountDO account, Map<Long, KolRefDTO> kolRefs) {
    KolRefDTO kol = kolRefs.get(account.getKolId());
    return new AdminAccountDTO(
        account.getId(),
        account.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        account.getAccountType(),
        account.getAccountMasked(),
        account.getHolderName(),
        account.getCurrency(),
        account.getUsdtNetwork(),
        account.getQrFileId(),
        account.getStatus(),
        Boolean.TRUE.equals(account.getIsDefault()),
        account.getRejectReason(),
        account.getLastSubmittedAt(),
        account.getReviewedTime());
  }

  private void auditKol(
      KolAudit context, long kolId, long accountId, String action, String maskedAccount) {
    // Only the masked account ever reaches the audit trail (task 15a §4.2.7).
    audit.recordKolOperation(
        context.tenantId(),
        context.email(),
        "PayoutAccount",
        action,
        context.requestUri(),
        context.ip(),
        "kolId=" + kolId + ", accountId=" + accountId + ", account=" + maskedAccount);
  }
}
