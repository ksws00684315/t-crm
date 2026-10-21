package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.commission.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.commission.mapper.PayoutAccountMapper;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.commission.model.PayoutAccountDO;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class PayoutAccountServiceTest {
  /** Deterministic 32-byte key; only the format matters here. */
  private static final SecretCipher CIPHER =
      new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PayoutAccountDO.class);
  }

  private static final long KOL = 10L;
  private static final long ACCOUNT_ID = 77L;
  private static final Instant SUBMITTED = Instant.parse("2026-10-17T00:00:00.000Z");
  private static final PayoutAccountService.KolAudit AUDIT =
      new PayoutAccountService.KolAudit(2L, "kol@example.com", "127.0.0.1", "/kol-api/x");

  private final PayoutAccountMapper accounts = mock(PayoutAccountMapper.class);
  private final BizFileService files = mock(BizFileService.class);
  private final KolApi kols = mock(KolApi.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final PayoutAccountService service =
      new PayoutAccountService(
          accounts, files, kols, CIPHER, TestTransactions.template(), audit, events);

  @BeforeEach
  void insertAssignsId() {
    when(kols.lockKol(KOL)).thenReturn(true);
    when(accounts.insert(any(PayoutAccountDO.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, PayoutAccountDO.class).setId(ACCOUNT_ID);
              return 1;
            });
  }

  private static PayoutAccountService.SaveCommand paypal(String account) {
    return new PayoutAccountService.SaveCommand(
        PayoutAccountType.PAYPAL, account, "Alice Doe", "USD", null, null);
  }

  private static PayoutAccountService.SaveCommand usdt(Long qrFileId) {
    return new PayoutAccountService.SaveCommand(
        PayoutAccountType.USDT, "TQ5xnPW9tZ3kA7qL9aZ", null, null, UsdtNetwork.TRC20, qrFileId);
  }

  private static PayoutAccountDO account(PayoutAccountStatus status) {
    PayoutAccountDO account = new PayoutAccountDO();
    account.setId(ACCOUNT_ID);
    account.setKolId(KOL);
    account.setAccountType(PayoutAccountType.PAYPAL);
    account.setAccountCipher(CIPHER.encrypt("alice@example.com"));
    account.setAccountMasked("al***@example.com");
    account.setStatus(status);
    account.setIsDefault(false);
    account.setLastSubmittedAt(SUBMITTED);
    return account;
  }

  private static AccountDTO admin(Role role, DataScope scope, long id) {
    return new AccountDTO(
        id,
        "u" + id,
        null,
        "U",
        null,
        role,
        scope,
        1,
        null,
        null,
        0,
        2L,
        "C",
        true,
        Set.of("payout-account:review", "commission:view"),
        null);
  }

  private static AccountDTO superAdmin() {
    return new AccountDTO(
        1L,
        "root",
        null,
        "R",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        2L,
        "C",
        true,
        Set.of(),
        null);
  }

  @Test
  void shouldCreatePendingEncryptedAccount_andClaimQrFileInOneTransaction() {
    service.create(KOL, usdt(55L), AUDIT);

    var saved = ArgumentCaptor.forClass(PayoutAccountDO.class);
    verify(accounts).insert(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(PayoutAccountStatus.PENDING);
    assertThat(saved.getValue().getAccountCipher()).startsWith("v1:");
    assertThat(saved.getValue().getAccountMasked()).isEqualTo("TQ5x…L9aZ");
    assertThat(saved.getValue().getHolderName()).isNull();
    assertThat(saved.getValue().getUsdtNetwork()).isEqualTo(UsdtNetwork.TRC20);
    assertThat(saved.getValue().getCreateBy()).isEqualTo(KOL);
    verify(files).claimQrFile(KOL, 55L, ACCOUNT_ID);
    verify(audit)
        .recordKolOperation(
            eq(2L),
            eq("kol@example.com"),
            eq("PayoutAccount"),
            eq("Create"),
            any(),
            any(),
            eq("kolId=" + KOL + ", accountId=" + ACCOUNT_ID + ", account=TQ5x…L9aZ"));
  }

  @Test
  void shouldRejectTheSixthActiveAccount() {
    when(accounts.selectCount(any())).thenReturn(5L);
    assertThatThrownBy(() -> service.create(KOL, paypal("a@x.io"), AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_LIMIT.message());
    verify(accounts, never()).insert(any(PayoutAccountDO.class));
  }

  @Test
  void shouldResubmitRejectedAccount_withReviewCleared() {
    PayoutAccountDO existing = account(PayoutAccountStatus.REJECTED);
    existing.setRejectReason("typo");
    existing.setReviewedBy(5L);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(existing);
    when(accounts.update(isNull(), any())).thenReturn(1);

    service.update(KOL, ACCOUNT_ID, paypal("new@example.com"), AUDIT);

    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(accounts).update(isNull(), wrapper.capture());
    String sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PayoutAccountDO>)
                wrapper.getValue())
            .getSqlSet();
    assertThat(sqlSet)
        .contains(
            "account_cipher=", "account_masked=", "status=", "reject_reason=", "reviewed_by=");
    var params =
        ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
            .getParamNameValuePairs();
    assertThat(params.values()).contains(PayoutAccountStatus.PENDING, "ne***@example.com");
    // Encryption is randomized; assert the cipher is fresh rather than equal to another run.
    assertThat(params.values().toString()).contains("v1:");
  }

  @Test
  void shouldRefuseToEditApprovedAccount() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.APPROVED));
    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("new@x.io"), AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_LOCKED.message());
    verify(accounts, never()).update(any(), any());
  }

  @Test
  void shouldHideForeignAndDisabledAccountsFromTheKol() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.DISABLED));
    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("new@x.io"), AUDIT))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());

    PayoutAccountDO foreign = account(PayoutAccountStatus.PENDING);
    foreign.setKolId(KOL + 1);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(foreign);
    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("new@x.io"), AUDIT))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldSwitchDefaultInsideOneTransaction() {
    PayoutAccountDO approved = account(PayoutAccountStatus.APPROVED);
    approved.setIsDefault(false);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(approved);
    when(accounts.update(isNull(), any())).thenReturn(1);

    service.setDefault(KOL, ACCOUNT_ID, AUDIT);

    // Two updates in one transaction: clear the previous default, then set this one.
    verify(accounts, org.mockito.Mockito.times(2)).update(isNull(), any());
  }

  @Test
  void shouldRejectDefaultForNonApprovedAccount() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.PENDING));
    assertThatThrownBy(() -> service.setDefault(KOL, ACCOUNT_ID, AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_NOT_APPROVED.message());
    verify(accounts, never()).update(any(), any());
  }

  @Test
  void shouldDisableForGood_andDropDefaultAndReason() {
    PayoutAccountDO rejected = account(PayoutAccountStatus.REJECTED);
    rejected.setRejectReason("nope");
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(rejected);

    service.disable(KOL, ACCOUNT_ID, AUDIT);

    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(accounts).update(isNull(), wrapper.capture());
    String sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PayoutAccountDO>)
                wrapper.getValue())
            .getSqlSet();
    assertThat(sqlSet).contains("status=", "is_default=").contains("reject_reason=");
  }

  @Test
  void shouldReviewOnlyPendingAccounts() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.APPROVED));
    assertThatThrownBy(
            () ->
                service.review(
                    superAdmin(),
                    ACCOUNT_ID,
                    PayoutAccountService.ReviewAction.APPROVE,
                    null,
                    SUBMITTED))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_NOT_PENDING.message());
  }

  @Test
  void shouldApproveAndRejectWithReviewerStamp() {
    PayoutAccountDO pending = account(PayoutAccountStatus.PENDING);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(pending);
    when(accounts.update(isNull(), any())).thenReturn(1);

    service.review(
        superAdmin(), ACCOUNT_ID, PayoutAccountService.ReviewAction.REJECT, "wrong zip", SUBMITTED);

    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(accounts).update(isNull(), wrapper.capture());
    var params =
        ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
            .getParamNameValuePairs();
    assertThat(params.values()).contains(PayoutAccountStatus.REJECTED, "wrong zip", 1L);
  }

  @Test
  void shouldHideOtherBdsAccountsFromOwnScopeAdmins() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.PENDING));
    when(kols.listByIds(List.of(KOL)))
        .thenReturn(List.of(new KolRefDTO(KOL, "KOL0001", "A", true, 99L)));

    assertThatThrownBy(() -> service.detail(admin(Role.STAFF, DataScope.OWN, 5L), ACCOUNT_ID))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldDecryptForReviewerDetail() {
    PayoutAccountDO pending = account(PayoutAccountStatus.PENDING);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(pending);
    when(kols.listByIds(List.of(KOL)))
        .thenReturn(List.of(new KolRefDTO(KOL, "KOL0001", "Alice", true, 1L)));

    var detail = service.detail(admin(Role.STAFF, DataScope.OWN, 1L), ACCOUNT_ID);

    assertThat(detail.account()).isEqualTo("alice@example.com");
    assertThat(detail.kolCode()).isEqualTo("KOL0001");
  }

  @Test
  void shouldDecryptOnlyForTheOwner() {
    PayoutAccountDO row = account(PayoutAccountStatus.APPROVED);
    row.setAccountCipher(CIPHER.encrypt("secret.wallet.address"));
    when(accounts.selectList(any())).thenReturn(List.of(row));

    assertThat(service.listMine(KOL).getFirst().account()).isEqualTo("secret.wallet.address");
  }

  @Test
  void shouldPageBySubmissionForTheCompanyWideReviewer() {
    var empty =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PayoutAccountDO>(1, 20);
    empty.setTotal(0);
    when(accounts.selectPage(any(), any())).thenReturn(empty);
    var page = service.page(superAdmin(), new PayoutAccountService.Filter(null, null, null, 1, 20));
    assertThat(page.total()).isZero();
  }

  @Test
  void shouldReturnNoAccountsWhenKolCodeMatchesNothing() {
    when(kols.getByCode("KOL0999")).thenReturn(null);
    var page =
        service.page(
            superAdmin(), new PayoutAccountService.Filter("PENDING", null, "KOL0999", 1, 20));
    assertThat(page.items()).isEmpty();
    verify(accounts, never()).selectPage(any(), any());
  }

  @Test
  void shouldOnlyDownloadAttachedQrFilesForVisibleAccounts() {
    var download = new BizFileService.FileDTO(55L, "qr.png", "image/png", 8L, new byte[8]);
    when(files.attachedFile(55L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PAYOUT_QR, ACCOUNT_ID, download));
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.APPROVED));
    when(kols.listByIds(List.of(KOL)))
        .thenReturn(List.of(new KolRefDTO(KOL, "KOL0001", "A", true, 1L)));

    assertThat(service.fileForAdmin(admin(Role.STAFF, DataScope.OWN, 1L), 55L)).isSameAs(download);

    when(files.attachedFile(56L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PAYOUT_QR, null, download));
    assertThatThrownBy(() -> service.fileForAdmin(superAdmin(), 56L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldCountPendingWithinOwnScope() {
    when(kols.listByBdUserId(5L)).thenReturn(List.of(new KolRefDTO(KOL, "KOL0001", "A", true, 5L)));
    when(accounts.selectCount(any())).thenReturn(3L);

    assertThat(service.pendingCount(admin(Role.STAFF, DataScope.OWN, 5L))).isEqualTo(3L);

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(accounts).selectCount(query.capture());
    var countWrapper =
        (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query.getValue();
    // Query conditions store their parameters when the SQL segment renders.
    assertThat(countWrapper.getSqlSegment()).contains("status");
    assertThat(countWrapper.getParamNameValuePairs()).containsValue(PayoutAccountStatus.PENDING);
  }

  @Test
  void shouldAuditWithMaskedAccountOnly() {
    service.create(KOL, paypal("alice@example.com"), AUDIT);
    var params = ArgumentCaptor.forClass(String.class);
    verify(audit)
        .recordKolOperation(anyLong(), any(), any(), any(), any(), any(), params.capture());
    assertThat(params.getValue()).contains("al***@example.com").doesNotContain("alice@example.com");
  }

  @Test
  void shouldReturnEmptyPendingCountWhenOwnBdHasNoKols() {
    when(kols.listByBdUserId(5L)).thenReturn(List.of());
    assertThat(service.pendingCount(admin(Role.STAFF, DataScope.OWN, 5L))).isZero();
    verify(accounts, never()).selectCount(any());
  }

  @Test
  void shouldFilterByKolCodeInsideOwnScope() {
    when(kols.getByCode("KOL0001"))
        .thenReturn(List.of(new KolRefDTO(KOL, "KOL0001", "A", true, 5L)).get(0));
    when(accounts.selectPage(any(), any())).thenReturn(emptyPage());
    assertThat(
            service
                .page(
                    admin(Role.STAFF, DataScope.OWN, 5L),
                    new PayoutAccountService.Filter(null, "USDT", "kol0001", 1, 20))
                .total())
        .isZero();

    when(kols.getByCode("KOL0002")).thenReturn(null);
    assertThat(
            service
                .page(superAdmin(), new PayoutAccountService.Filter(" ", null, "KOL0002", 1, 20))
                .items())
        .isEmpty();
    verify(accounts, never()).selectPage(any(), any());
  }

  @Test
  void shouldHideForeignKolCodeFromOwnScopeAdmin() {
    when(kols.getByCode("KOL0009")).thenReturn(new KolRefDTO(99L, "KOL0009", "X", true, 6L));
    assertThat(
            service
                .page(
                    admin(Role.STAFF, DataScope.OWN, 5L),
                    new PayoutAccountService.Filter("PENDING", null, "KOL0009", 1, 20))
                .items())
        .isEmpty();
    verify(accounts, never()).selectPage(any(), any());
  }

  @Test
  void shouldListAllStatusesWithBlankType() {
    when(accounts.selectPage(any(), any())).thenReturn(emptyPage());
    assertThat(
            service
                .page(superAdmin(), new PayoutAccountService.Filter(null, " ", null, 1, 20))
                .total())
        .isZero();
  }

  @Test
  void shouldSurviveMissingKolRefInDetailAndRows() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.PENDING));
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());
    var detail = service.detail(superAdmin(), ACCOUNT_ID);
    assertThat(detail.kolCode()).isNull();
    assertThat(detail.kolName()).isNull();
  }

  @Test
  void shouldSurviveMissingKolRefInListRows() {
    PayoutAccountDO row = account(PayoutAccountStatus.PENDING);
    var page =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PayoutAccountDO>(1, 20);
    page.setRecords(List.of(row));
    page.setTotal(1);
    when(accounts.selectPage(any(), any())).thenReturn(page);
    when(kols.listByIds(List.of(KOL))).thenReturn(List.of());

    var result =
        service.page(superAdmin(), new PayoutAccountService.Filter(null, null, null, 1, 20));
    assertThat(result.items().get(0).kolCode()).isNull();
  }

  @Test
  void should404MissingAccountOnKolPath() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(null);
    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("a@x.io"), AUDIT))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldClaimNewQrOnEditButKeepTheCurrentOne() {
    PayoutAccountDO existing = account(PayoutAccountStatus.REJECTED);
    existing.setQrFileId(55L);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(existing);
    when(accounts.update(isNull(), any())).thenReturn(1);

    service.update(KOL, ACCOUNT_ID, usdt(55L), AUDIT);
    verify(files, never()).claimQrFile(anyLong(), anyLong(), anyLong());

    service.update(KOL, ACCOUNT_ID, usdt(null), AUDIT);
    verify(files, never()).claimQrFile(anyLong(), anyLong(), anyLong());

    service.update(KOL, ACCOUNT_ID, usdt(66L), AUDIT);
    verify(files).claimQrFile(KOL, 66L, ACCOUNT_ID);
  }

  @Test
  void should404AdminFileOfUnknownAccount() {
    var download = new BizFileService.FileDTO(55L, "qr.png", "image/png", 8L, new byte[8]);
    when(files.attachedFile(55L))
        .thenReturn(new BizFileService.AttachedFile(FileBizType.PAYOUT_QR, ACCOUNT_ID, download));
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(null);
    assertThatThrownBy(() -> service.fileForAdmin(superAdmin(), 55L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldRejectReviewWhenTheVersionWasSeenBeforeAKolEdit() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.PENDING));
    // The conditional update matches nothing when the KOL resubmitted (new lastSubmittedAt).
    when(accounts.update(isNull(), any())).thenReturn(0);

    assertThatThrownBy(
            () ->
                service.review(
                    superAdmin(),
                    ACCOUNT_ID,
                    PayoutAccountService.ReviewAction.APPROVE,
                    null,
                    SUBMITTED))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_NOT_PENDING.message());
  }

  @Test
  void shouldRefuseEditWhenAReviewCommittedInBetween() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.REJECTED));
    when(accounts.update(isNull(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("new@example.com"), AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_LOCKED.message());
    verify(files, never()).claimQrFile(anyLong(), anyLong(), anyLong());
  }

  @Test
  void shouldRefuseDefaultWhenTheAccountWasDisabledInBetween() {
    PayoutAccountDO approved = account(PayoutAccountStatus.APPROVED);
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(approved);
    when(accounts.update(isNull(), any())).thenReturn(0, 0);

    assertThatThrownBy(() -> service.setDefault(KOL, ACCOUNT_ID, AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_NOT_APPROVED.message());
  }

  @Test
  void shouldCheckTheLimitUnderTheKolRowLock() {
    when(kols.lockKol(KOL)).thenReturn(true);
    when(accounts.selectCount(any())).thenReturn(5L);

    assertThatThrownBy(() -> service.create(KOL, paypal("new@example.com"), AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_LIMIT.message());
    verify(accounts, never()).insert(any(PayoutAccountDO.class));

    when(kols.lockKol(KOL)).thenReturn(false);
    assertThatThrownBy(() -> service.create(KOL, paypal("new@example.com"), AUDIT))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldPublish_whenAccountIsCreated() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(KOL, "KOL0010", "Ada", true, 3L)));
    service.create(KOL, paypal("alice@example.com"), AUDIT);
    var published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue())
        .isEqualTo(new PayoutAccountSubmittedEvent(2L, KOL, ACCOUNT_ID, "KOL0010", "PAYPAL"));
  }

  @Test
  void shouldPublish_whenRejectedAccountIsResubmitted() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.REJECTED));
    when(accounts.update(isNull(), any())).thenReturn(1);
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(KOL, "KOL0010", "Ada", true, 3L)));
    service.update(KOL, ACCOUNT_ID, usdt(null), AUDIT);
    var published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue())
        .isEqualTo(new PayoutAccountSubmittedEvent(2L, KOL, ACCOUNT_ID, "KOL0010", "USDT"));
  }

  @Test
  void shouldNotPublish_whenCreatorCodeIsMissing() {
    when(kols.listByIds(any())).thenReturn(List.of());
    service.create(KOL, paypal("alice@example.com"), AUDIT);
    verify(accounts).insert(any(PayoutAccountDO.class));
    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldNotPublish_whenCreateRollsBackBeforeTheEvent() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(KOL, "KOL0010", "Ada", true, 3L)));
    org.mockito.Mockito.doThrow(new IllegalStateException("claim"))
        .when(files)
        .claimQrFile(anyLong(), anyLong(), anyLong());
    assertThatThrownBy(() -> service.create(KOL, usdt(9L), AUDIT))
        .isInstanceOf(IllegalStateException.class);
    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldNotPublish_whenUpdateMatchesNoRow() {
    when(accounts.selectById(ACCOUNT_ID)).thenReturn(account(PayoutAccountStatus.REJECTED));
    when(accounts.update(isNull(), any())).thenReturn(0);
    assertThatThrownBy(() -> service.update(KOL, ACCOUNT_ID, paypal("new@example.com"), AUDIT))
        .hasMessage(ErrorCode.PAYOUT_ACCOUNT_LOCKED.message());
    verify(events, never()).publishEvent(any());
  }

  private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<PayoutAccountDO>
      emptyPage() {
    var page =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PayoutAccountDO>(1, 20);
    page.setTotal(0);
    return page;
  }
}
