package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.CopyTargetShopApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolCopyTokenMapper;
import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.model.KolCopyTokenDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CopyTokenServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolCopyTokenDO.class);
  }

  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
  private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

  private final KolCopyTokenMapper tokens = mock(KolCopyTokenMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final CopyTargetShopApi shops = mock(CopyTargetShopApi.class);
  private final SecretCipher cipher = new SecretCipher(SECRET);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final CopyTokenPlaintexts plaintexts =
      new CopyTokenPlaintexts(new SecureRandom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
  private final CopyTokenService service =
      new CopyTokenService(
          tokens,
          kols,
          shops,
          cipher,
          current,
          1,
          1440,
          plaintexts,
          Clock.fixed(NOW, ZoneOffset.UTC));
  private TenantScope.Scope companyScope;

  @BeforeEach
  void setUp() {
    when(current.user()).thenReturn(admin());
    companyScope = TenantScope.openCompany(1L);
  }

  @Test
  void shouldUseCurrentConfiguredDefaultsForNewToken() {
    var configs = mock(com.tcrm.admin.shared.api.SystemConfigApi.class);
    when(configs.getInt("copy-token.default-quota")).thenReturn(17);
    when(configs.getInt("copy-token.default-valid-minutes")).thenReturn(240);
    when(configs.getInt("copy-token.max-quota")).thenReturn(100000);
    when(configs.getInt("copy-token.max-valid-minutes")).thenReturn(10080);
    var configuredService = new CopyTokenService(tokens, kols, shops, cipher, current, configs);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, true, 99L)));
    when(tokens.insert(any(KolCopyTokenDO.class)))
        .thenAnswer(
            invocation -> {
              KolCopyTokenDO row = invocation.getArgument(0);
              row.setId(100L);
              row.setCreateTime(NOW);
              return 1;
            });

    CreatedCopyTokenDTO created =
        configuredService.create(new CreateCopyTokenCommand(10L, null, null, null));

    assertThat(created.row().quota()).isEqualTo(17);
    assertThat(created.row().validMinutes()).isEqualTo(240);
    verify(configs).getInt("copy-token.default-quota");
    verify(configs).getInt("copy-token.default-valid-minutes");
  }

  @AfterEach
  void tearDown() {
    companyScope.close();
  }

  @Test
  void shouldCreateWithDefaults_andStoreHashAndCipherOnly() {
    KolRefDTO kol = kol(10L, true, 99L);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol));
    when(tokens.insert(any(KolCopyTokenDO.class)))
        .thenAnswer(
            inv -> {
              KolCopyTokenDO row = inv.getArgument(0);
              row.setId(100L);
              row.setCreateTime(NOW);
              return 1;
            });

    CreatedCopyTokenDTO created = service.create(new CreateCopyTokenCommand(10L, null, null, null));

    assertThat(CopyTokenPlaintexts.looksValid(created.token())).isTrue();
    assertThat(created.row().tokenHint()).isEqualTo(CopyTokenPlaintexts.hint(created.token()));
    assertThat(created.row().quota()).isEqualTo(1);
    assertThat(created.row().validMinutes()).isEqualTo(1440);
    assertThat(created.row().displayStatus()).isEqualTo(CopyTokenDisplayStatus.ACTIVE);

    ArgumentCaptor<KolCopyTokenDO> inserted = ArgumentCaptor.forClass(KolCopyTokenDO.class);
    verify(tokens).insert(inserted.capture());
    KolCopyTokenDO row = inserted.getValue();
    assertThat(row.getTokenHash()).isEqualTo(CopyTokenPlaintexts.sha256Hex(created.token()));
    assertThat(cipher.decrypt(row.getTokenCipher())).isEqualTo(created.token());
    assertThat(row.getTokenCipher()).doesNotContain(created.token().substring(4));
  }

  @Test
  void shouldRejectDisabledKol_andForeignShop_andOutOfRange() {
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, false, 99L)));
    assertThatThrownBy(() -> service.create(new CreateCopyTokenCommand(10L, 10, 60, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_DISABLED.message());

    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, true, 99L)));
    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SHOP_NOT_FOUND))
        .when(shops)
        .requireEnabled(5L);
    assertThatThrownBy(() -> service.create(new CreateCopyTokenCommand(10L, 10, 60, 5L)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.SHOP_NOT_FOUND.message());

    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SHOP_DISABLED))
        .when(shops)
        .requireEnabled(5L);
    assertThatThrownBy(() -> service.create(new CreateCopyTokenCommand(10L, 10, 60, 5L)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.SHOP_DISABLED.message());

    assertThatThrownBy(() -> service.create(new CreateCopyTokenCommand(10L, 0, 60, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
  }

  @Test
  void shouldCreateWithTargetShop_whenShopEnabled() {
    KolRefDTO kol = kol(10L, true, 99L);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol));
    when(shops.namesByIds(List.of(5L))).thenReturn(Map.of(5L, "Shop A"));
    when(tokens.insert(any(KolCopyTokenDO.class)))
        .thenAnswer(
            inv -> {
              KolCopyTokenDO row = inv.getArgument(0);
              row.setId(101L);
              row.setCreateTime(NOW);
              return 1;
            });

    CreatedCopyTokenDTO created = service.create(new CreateCopyTokenCommand(10L, null, null, 5L));

    assertThat(created.row().targetShopId()).isEqualTo(5L);
    assertThat(created.row().targetShopName()).isEqualTo("Shop A");
    verify(shops).requireEnabled(5L);
  }

  @Test
  void shouldHideOtherBdTokens_forOwnScope() {
    when(current.user()).thenReturn(bd(8L));
    when(kols.listByBdUserId(8L)).thenReturn(List.of(kol(10L, true, 8L)));
    when(tokens.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
        .thenReturn(new Page<>(1, 20));

    service.page(new CopyTokenFilterDTO(null, null, null, null, 1, 20));

    ArgumentCaptor<LambdaQueryWrapper<KolCopyTokenDO>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(tokens).selectPage(any(Page.class), captor.capture());
  }

  @Test
  void shouldReturn404_whenOwnScopeTouchesForeignToken() {
    when(current.user()).thenReturn(bd(8L));
    KolCopyTokenDO row =
        stored(100L, 11L, KolCopyTokenDO.STATUS_ACTIVE, 0, 10, NOW.plusSeconds(60));
    when(tokens.selectById(100L)).thenReturn(row);
    when(kols.listByIds(List.of(11L))).thenReturn(List.of(kol(11L, true, 9L)));

    assertThatThrownBy(() -> service.reveal(100L))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    assertThatThrownBy(() -> service.revoke(100L))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldRevealViaAuthenticate_andRejectRevokedTwice() {
    String plaintext = plaintexts.generate();
    KolCopyTokenDO row =
        stored(100L, 10L, KolCopyTokenDO.STATUS_ACTIVE, 0, 10, NOW.plusSeconds(3600));
    row.setTokenHash(CopyTokenPlaintexts.sha256Hex(plaintext));
    row.setTokenCipher(cipher.encrypt(plaintext));
    row.setTokenHint(CopyTokenPlaintexts.hint(plaintext));
    when(tokens.selectById(100L)).thenReturn(row);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, true, 99L)));
    when(tokens.selectOne(any())).thenReturn(row);
    when(tokens.update(eq(null), any())).thenReturn(1);

    assertThat(service.reveal(100L)).isEqualTo(plaintext);
    CopyTokenGrantDTO grant = service.authenticate(plaintext);
    assertThat(grant.tokenId()).isEqualTo(100L);
    assertThat(grant.remaining()).isEqualTo(10);

    service.revoke(100L);
    when(tokens.selectById(100L)).thenReturn(row);
    row.setStatus(KolCopyTokenDO.STATUS_REVOKED);
    assertThatThrownBy(() -> service.revoke(100L))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_CONFLICT.message());
  }

  @Test
  void shouldMapAuthenticateErrors() {
    String plaintext = plaintexts.generate();
    when(tokens.selectOne(any())).thenReturn(null);
    assertThatThrownBy(() -> service.authenticate(plaintext))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COPY_TOKEN_INVALID.message());

    KolCopyTokenDO expired =
        stored(1L, 10L, KolCopyTokenDO.STATUS_ACTIVE, 0, 10, NOW.minusSeconds(1));
    expired.setTokenHash(CopyTokenPlaintexts.sha256Hex(plaintext));
    when(tokens.selectOne(any())).thenReturn(expired);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, true, 1L)));
    assertThatThrownBy(() -> service.authenticate(plaintext))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COPY_TOKEN_EXPIRED.message());

    KolCopyTokenDO exhausted =
        stored(1L, 10L, KolCopyTokenDO.STATUS_ACTIVE, 10, 10, NOW.plusSeconds(60));
    exhausted.setTokenHash(CopyTokenPlaintexts.sha256Hex(plaintext));
    when(tokens.selectOne(any())).thenReturn(exhausted);
    assertThatThrownBy(() -> service.authenticate(plaintext))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COPY_TOKEN_QUOTA_EXCEEDED.message());

    KolCopyTokenDO active =
        stored(1L, 10L, KolCopyTokenDO.STATUS_ACTIVE, 0, 10, NOW.plusSeconds(60));
    active.setTokenHash(CopyTokenPlaintexts.sha256Hex(plaintext));
    when(tokens.selectOne(any())).thenReturn(active);
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(kol(10L, false, 1L)));
    assertThatThrownBy(() -> service.authenticate(plaintext))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_DISABLED.message());
  }

  @Test
  void shouldNotConsumeWhenQuotaInsufficient() {
    when(tokens.consume(eq(1L), eq(5), any())).thenReturn(0);
    assertThat(service.consume(1L, 5)).isFalse();
    when(tokens.consume(eq(1L), eq(1), any())).thenReturn(1);
    assertThat(service.consume(1L, 1)).isTrue();
    assertThat(service.consume(1L, 0)).isFalse();
    verify(tokens, never()).consume(eq(1L), eq(0), any());
  }

  private static KolCopyTokenDO stored(
      long id, long kolId, String status, int used, int quota, Instant expires) {
    KolCopyTokenDO row = new KolCopyTokenDO();
    row.setId(id);
    row.setTenantId(1L);
    row.setKolId(kolId);
    row.setStatus(status);
    row.setUsed(used);
    row.setQuota(quota);
    row.setExpiresAt(expires);
    row.setValidMinutes(60);
    row.setTargetChannel(KolCopyTokenDO.CHANNEL_WEIDIAN);
    row.setTokenHint("TKN-AAAA****ZZZZ");
    row.setTokenHash("a".repeat(64));
    row.setTokenCipher("v1:x");
    row.setCreateTime(NOW);
    return row;
  }

  private static KolRefDTO kol(long id, boolean enabled, Long bd) {
    return new KolRefDTO(id, "KOL" + id, "Name" + id, enabled, bd);
  }

  private static AccountDTO admin() {
    return new AccountDTO(
        1L,
        "admin",
        "hash",
        "Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        1L,
        "Acme",
        true);
  }

  private static AccountDTO bd(long id) {
    return new AccountDTO(
        id,
        "bd",
        "hash",
        "BD",
        null,
        Role.STAFF,
        DataScope.OWN,
        1,
        null,
        null,
        0,
        1L,
        "Acme",
        true);
  }
}
