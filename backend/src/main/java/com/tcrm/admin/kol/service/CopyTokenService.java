package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.CopyTargetShopApi;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolCopyTokenMapper;
import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.model.KolCopyTokenDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.MissingTenantException;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy-token lifecycle and the internal {@link CopyTokenApi} used by task 17b (task 17a §4.4–§4.5).
 */
@Service
public class CopyTokenService implements CopyTokenApi {
  static final int MIN_QUOTA = 1;
  static final int MAX_QUOTA = 100_000;
  static final int MIN_MINUTES = 5;
  static final int MAX_MINUTES = 10_080;

  private final KolCopyTokenMapper tokens;
  private final KolApi kols;
  private final CopyTargetShopApi shops;
  private final SecretCipher cipher;
  private final CurrentUserContext current;
  private final int defaultQuota;
  private final int defaultValidMinutes;
  private final CopyTokenPlaintexts plaintexts;
  private final Clock clock;
  private final SystemConfigApi configs;

  @Autowired
  public CopyTokenService(
      KolCopyTokenMapper tokens,
      KolApi kols,
      CopyTargetShopApi shops,
      SecretCipher cipher,
      CurrentUserContext current,
      SystemConfigApi configs) {
    this.tokens = tokens;
    this.kols = kols;
    this.shops = shops;
    this.cipher = cipher;
    this.current = current;
    this.defaultQuota = 1;
    this.defaultValidMinutes = 1440;
    this.plaintexts = new CopyTokenPlaintexts();
    this.clock = Clock.systemUTC();
    this.configs = configs;
  }

  CopyTokenService(
      KolCopyTokenMapper tokens,
      KolApi kols,
      CopyTargetShopApi shops,
      SecretCipher cipher,
      CurrentUserContext current,
      int defaultQuota,
      int defaultValidMinutes,
      CopyTokenPlaintexts plaintexts,
      Clock clock) {
    this.tokens = tokens;
    this.kols = kols;
    this.shops = shops;
    this.cipher = cipher;
    this.current = current;
    this.defaultQuota = defaultQuota;
    this.defaultValidMinutes = defaultValidMinutes;
    this.plaintexts = plaintexts;
    this.clock = clock;
    this.configs = null;
  }

  public PageResult<CopyTokenRowDTO> page(CopyTokenFilterDTO filter) {
    AccountDTO user = current.user();
    LambdaQueryWrapper<KolCopyTokenDO> query = new LambdaQueryWrapper<>();
    if (filter.hint() != null && !filter.hint().isBlank()) {
      query.like(KolCopyTokenDO::getTokenHint, filter.hint().strip());
    }
    if (filter.kolId() != null) {
      query.eq(KolCopyTokenDO::getKolId, filter.kolId());
    }
    if (filter.targetChannel() != null && !filter.targetChannel().isBlank()) {
      query.eq(KolCopyTokenDO::getTargetChannel, filter.targetChannel().strip());
    }
    applyDisplayStatus(query, filter.status());
    List<Long> visible = visibleKolIds(user, filter.kolId());
    if (visible != null) {
      if (visible.isEmpty()) {
        return new PageResult<>(List.of(), 0, filter.page(), filter.size());
      }
      query.in(KolCopyTokenDO::getKolId, visible);
    }
    query.orderByDesc(KolCopyTokenDO::getCreateTime, KolCopyTokenDO::getId);
    var result = tokens.selectPage(new Page<>(filter.page(), filter.size()), query);
    Map<Long, KolRefDTO> kolRefs = kolRefs(result.getRecords());
    Map<Long, String> shopNames = shopNames(result.getRecords());
    Instant now = now();
    return new PageResult<>(
        result.getRecords().stream().map(row -> toRow(row, kolRefs, shopNames, now)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  @Transactional(rollbackFor = Exception.class)
  public CreatedCopyTokenDTO create(CreateCopyTokenCommand command) {
    AccountDTO user = current.user();
    Long tenantId = user.tenantId();
    if (tenantId == null) {
      throw new MissingTenantException("copy token creation requires a company");
    }
    int quota =
        command.quota() == null
            ? configInt("copy-token.default-quota", defaultQuota)
            : command.quota();
    int minutes =
        command.validMinutes() == null
            ? configInt("copy-token.default-valid-minutes", defaultValidMinutes)
            : command.validMinutes();
    if (quota < MIN_QUOTA
        || quota > configInt("copy-token.max-quota", MAX_QUOTA)
        || minutes < MIN_MINUTES
        || minutes > configInt("copy-token.max-valid-minutes", MAX_MINUTES)
        || command.kolId() == null
        || command.kolId() <= 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolRefDTO kol = requireVisibleKol(user, command.kolId());
    if (!kol.enabled()) {
      throw new BusinessException(ErrorCode.KOL_DISABLED);
    }
    Long shopId = command.targetShopId();
    if (shopId != null) {
      requireEnabledShop(shopId);
    }
    Instant now = now();
    Instant expiresAt = now.plus(minutes, ChronoUnit.MINUTES);
    for (int attempt = 0; attempt < 5; attempt++) {
      String plaintext = plaintexts.generate();
      KolCopyTokenDO row = new KolCopyTokenDO();
      row.setTenantId(tenantId);
      row.setKolId(kol.id());
      row.setTokenHash(CopyTokenPlaintexts.sha256Hex(plaintext));
      row.setTokenCipher(cipher.encrypt(plaintext));
      row.setTokenHint(CopyTokenPlaintexts.hint(plaintext));
      row.setTargetChannel(KolCopyTokenDO.CHANNEL_WEIDIAN);
      row.setTargetShopId(shopId);
      row.setQuota(quota);
      row.setUsed(0);
      row.setValidMinutes(minutes);
      row.setExpiresAt(expiresAt);
      row.setStatus(KolCopyTokenDO.STATUS_ACTIVE);
      row.setDeleted(false);
      try {
        tokens.insert(row);
      } catch (DuplicateKeyException e) {
        continue;
      }
      Map<Long, KolRefDTO> kolMap = Map.of(kol.id(), kol);
      Map<Long, String> shopMap = shopId == null ? Map.of() : shopNames(List.of(row));
      return new CreatedCopyTokenDTO(plaintext, toRow(row, kolMap, shopMap, now));
    }
    throw new IllegalStateException("Unable to allocate a unique copy token");
  }

  private int configInt(String key, int fallback) {
    return configs == null ? fallback : configs.getInt(key);
  }

  public String reveal(long id) {
    KolCopyTokenDO row = requireVisibleToken(current.user(), id);
    return cipher.decrypt(row.getTokenCipher());
  }

  @Transactional(rollbackFor = Exception.class)
  public void revoke(long id) {
    KolCopyTokenDO row = requireVisibleToken(current.user(), id);
    if (KolCopyTokenDO.STATUS_REVOKED.equals(row.getStatus())) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    Instant now = now();
    int updated =
        tokens.update(
            null,
            new LambdaUpdateWrapper<KolCopyTokenDO>()
                .eq(KolCopyTokenDO::getId, id)
                .eq(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_ACTIVE)
                .set(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_REVOKED)
                .set(KolCopyTokenDO::getUpdateTime, now));
    if (updated == 0) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
  }

  @Override
  public CopyTokenGrantDTO authenticate(String token) {
    if (token == null || token.isBlank()) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_INVALID);
    }
    String hash = CopyTokenPlaintexts.sha256Hex(token.strip());
    KolCopyTokenDO row =
        TenantScope.callSystem(
            "authenticate copy token by hash across companies",
            () ->
                tokens.selectOne(
                    new LambdaQueryWrapper<KolCopyTokenDO>()
                        .eq(KolCopyTokenDO::getTokenHash, hash)));
    if (row == null) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_INVALID);
    }
    return TenantScope.callCompany(row.getTenantId(), () -> grantOf(row));
  }

  @Override
  public boolean consume(long tokenId, int count) {
    if (tokenId <= 0 || count <= 0) {
      return false;
    }
    Instant now = now();
    // Prefer company scope when known; system scope covers callers without a session (17b).
    if (TenantScope.companyId() != null) {
      return tokens.consume(tokenId, count, now) > 0;
    }
    return TenantScope.callSystem(
        "consume copy token quota across companies", () -> tokens.consume(tokenId, count, now) > 0);
  }

  private CopyTokenGrantDTO grantOf(KolCopyTokenDO row) {
    Instant now = now();
    CopyTokenDisplayStatus display =
        CopyTokenDisplayStatus.of(
            row.getStatus(), row.getExpiresAt(), row.getUsed(), row.getQuota(), now);
    if (display == CopyTokenDisplayStatus.REVOKED) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_INVALID);
    }
    if (display == CopyTokenDisplayStatus.EXPIRED) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_EXPIRED);
    }
    if (display == CopyTokenDisplayStatus.EXHAUSTED) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_QUOTA_EXCEEDED);
    }
    List<KolRefDTO> refs = kols.listByIds(List.of(row.getKolId()));
    KolRefDTO kol = refs.isEmpty() ? null : refs.get(0);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COPY_TOKEN_INVALID);
    }
    if (!kol.enabled()) {
      throw new BusinessException(ErrorCode.KOL_DISABLED);
    }
    int remaining = row.getQuota() - row.getUsed();
    return new CopyTokenGrantDTO(
        row.getId(),
        row.getTenantId(),
        row.getKolId(),
        row.getTargetChannel(),
        row.getTargetShopId(),
        remaining);
  }

  private void applyDisplayStatus(
      LambdaQueryWrapper<KolCopyTokenDO> query, CopyTokenDisplayStatus status) {
    if (status == null) {
      return;
    }
    Instant now = now();
    switch (status) {
      case REVOKED -> query.eq(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_REVOKED);
      case EXPIRED ->
          query
              .eq(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_ACTIVE)
              .le(KolCopyTokenDO::getExpiresAt, now);
      case EXHAUSTED ->
          query
              .eq(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_ACTIVE)
              .gt(KolCopyTokenDO::getExpiresAt, now)
              .apply("used >= quota");
      case ACTIVE ->
          query
              .eq(KolCopyTokenDO::getStatus, KolCopyTokenDO.STATUS_ACTIVE)
              .gt(KolCopyTokenDO::getExpiresAt, now)
              .apply("used < quota");
      default -> throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  /**
   * Null = no restriction (ALL / super admin). Empty = OWN with no KOLs (or the requested KOL is
   * outside scope). Otherwise the visible KOL id list.
   */
  private List<Long> visibleKolIds(AccountDTO user, Long requestedKolId) {
    if (requestedKolId != null) {
      KolRefDTO kol = requireKolOrNull(requestedKolId);
      if (kol == null || (user.ownScope() && !Objects.equals(user.id(), kol.bdUserId()))) {
        return List.of();
      }
      return List.of(kol.id());
    }
    if (!user.ownScope()) {
      return null;
    }
    return kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).toList();
  }

  private KolRefDTO requireVisibleKol(AccountDTO user, long kolId) {
    KolRefDTO kol = requireKolOrNull(kolId);
    if (kol == null || (user.ownScope() && !Objects.equals(user.id(), kol.bdUserId()))) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return kol;
  }

  private KolCopyTokenDO requireVisibleToken(AccountDTO user, long id) {
    KolCopyTokenDO row = tokens.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    requireVisibleKol(user, row.getKolId());
    return row;
  }

  private KolRefDTO requireKolOrNull(long kolId) {
    List<KolRefDTO> refs = kols.listByIds(List.of(kolId));
    return refs.isEmpty() ? null : refs.get(0);
  }

  private void requireEnabledShop(long shopId) {
    shops.requireEnabled(shopId);
  }

  private Map<Long, KolRefDTO> kolRefs(List<KolCopyTokenDO> rows) {
    List<Long> ids = rows.stream().map(KolCopyTokenDO::getKolId).distinct().toList();
    Map<Long, KolRefDTO> byId = new HashMap<>();
    if (!ids.isEmpty()) {
      for (KolRefDTO kol : kols.listByIds(ids)) {
        byId.put(kol.id(), kol);
      }
    }
    return byId;
  }

  private Map<Long, String> shopNames(List<KolCopyTokenDO> rows) {
    List<Long> ids =
        rows.stream()
            .map(KolCopyTokenDO::getTargetShopId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return shops.namesByIds(ids);
  }

  private CopyTokenRowDTO toRow(
      KolCopyTokenDO row, Map<Long, KolRefDTO> kolRefs, Map<Long, String> shopNames, Instant now) {
    KolRefDTO kol = kolRefs.get(row.getKolId());
    String shopName = row.getTargetShopId() == null ? null : shopNames.get(row.getTargetShopId());
    return new CopyTokenRowDTO(
        row.getId(),
        row.getTokenHint(),
        row.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        row.getTargetChannel(),
        row.getTargetShopId(),
        shopName,
        row.getUsed(),
        row.getQuota(),
        row.getValidMinutes(),
        row.getExpiresAt(),
        CopyTokenDisplayStatus.of(
            row.getStatus(), row.getExpiresAt(), row.getUsed(), row.getQuota(), now),
        row.getLastUsedAt(),
        row.getCreateTime());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }
}
