package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.mapper.WeidianAppMapper;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianAppDO;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The single place that obtains, persists, and refreshes Weidian access tokens. */
@Service
public class WeidianTokenCenter {
  private static final Logger LOG = LoggerFactory.getLogger(WeidianTokenCenter.class);
  static final Duration RENEW_BEFORE = Duration.ofHours(3);
  static final Duration PASSIVE_REFRESH_WINDOW = Duration.ofMinutes(10);
  static final String LOCK_PREFIX = "weidian:token:";

  private final WeidianShopAuthMapper auths;
  private final WeidianAppMapper apps;
  private final ShopAccessApi shops;
  private final CurrentUserContext current;
  private final SecretCipher cipher;
  private final WeidianHttpGateway gateway;
  private final WeidianRateLimiter limiter;
  private final WeidianConnectivityChecker connectivity;
  private final DistributedLocks locks;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final Clock clock;

  @Autowired
  public WeidianTokenCenter(
      WeidianShopAuthMapper auths,
      WeidianAppMapper apps,
      ShopAccessApi shops,
      CurrentUserContext current,
      SecretCipher cipher,
      WeidianHttpGateway gateway,
      WeidianRateLimiter limiter,
      WeidianConnectivityChecker connectivity,
      DistributedLocks locks,
      StringRedisTemplate redis,
      ObjectMapper json) {
    this(
        auths,
        apps,
        shops,
        current,
        cipher,
        gateway,
        limiter,
        connectivity,
        locks,
        redis,
        json,
        Clock.systemUTC());
  }

  WeidianTokenCenter(
      WeidianShopAuthMapper auths,
      WeidianAppMapper apps,
      ShopAccessApi shops,
      CurrentUserContext current,
      SecretCipher cipher,
      WeidianHttpGateway gateway,
      WeidianRateLimiter limiter,
      WeidianConnectivityChecker connectivity,
      DistributedLocks locks,
      StringRedisTemplate redis,
      ObjectMapper json,
      Clock clock) {
    this.auths = auths;
    this.apps = apps;
    this.shops = shops;
    this.current = current;
    this.cipher = cipher;
    this.gateway = gateway;
    this.limiter = limiter;
    this.connectivity = connectivity;
    this.locks = locks;
    this.redis = redis;
    this.json = json;
    this.clock = clock;
  }

  /** Returns the current token, refreshing it centrally when it expires within three hours. */
  public WeidianToken accessToken(long shopId) {
    WeidianShopAuthDO auth = requireAuth(shopId);
    requireAuthorized(auth);
    if (needsRenewal(auth)) {
      return withShopLock(
          shopId,
          () -> {
            WeidianShopAuthDO fresh = requireAuth(shopId);
            requireAuthorized(fresh);
            if (needsRenewal(fresh)) {
              renewLocked(fresh);
            }
            return token(fresh);
          });
    }
    return token(auth);
  }

  /** Self-use app setup obtains a token and checks that the credentials can reach the shop. */
  public void configureSelfUse(long shopId, String appKey, String secret) {
    ShopAccessDTO shop = requireEnabledShop(shopId);
    if (appKey == null || appKey.isBlank() || secret == null || secret.isBlank()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    long operatorId = current.id();
    withShopLock(
        shopId,
        () -> {
          WeidianShopAuthDO auth = findAuth(shopId);
          if (auth == null) {
            auth = new WeidianShopAuthDO();
            auth.setShopId(shopId);
            auth.setTenantId(currentTenant());
            auth.setCreateBy(operatorId);
            auth.setCreateTime(clock.instant());
          }
          auth.setAuthMode(WeidianAuthMode.SELF_USE.name());
          auth.setSelfAppKey(appKey.strip());
          auth.setSelfAppSecretCipher(cipher.encrypt(secret));
          auth.setOpenid(null);
          auth.setRefreshTokenCipher(null);
          auth.setRefreshExpiresAt(null);
          auth.setStatus(WeidianAuthStatus.UNAUTHORIZED.name());
          auth.setAuthorizedBy(operatorId);
          auth.setLastError(null);
          save(auth);

          try {
            WeidianTokenPayload payload = obtainSelfToken(auth);
            applyPayload(auth, payload, clock.instant(), false);
            auth.setAuthorizedAt(clock.instant());
            auth.setLastRefreshAt(clock.instant());
            auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
            save(auth);
            WeidianConnectivityChecker.CheckResult result =
                connectivity.check(shop, appKey.strip(), payload.accessToken());
            recordConnectivity(auth, result);
          } catch (WeidianApiException e) {
            if (e.statusCode() == 10021 || e.statusCode() == 10026) {
              markExpired(auth, safeError(e));
            } else {
              markFailed(
                  auth, e.statusCode() == 10022 ? "Weidian credential error 10022" : safeError(e));
            }
            if (e.statusCode() == 10022) {
              throw new BusinessException(ErrorCode.WEIDIAN_CREDENTIAL_INVALID);
            }
            throw e;
          } catch (RuntimeException e) {
            markFailed(auth, "Weidian connectivity check failed");
            throw e;
          }
          return null;
        });
  }

  /** Exchanges a one-time service-app code. The returned object redacts credentials in toString. */
  public WeidianTokenPayload exchangeAuthorizationCode(long tenantId, String code) {
    WeidianAppDO app = TenantScope.callCompany(tenantId, this::requireApp);
    String secret = cipher.decrypt(app.getAppSecretCipher());
    LinkedHashMap<String, String> query = new LinkedHashMap<>();
    query.put("appkey", app.getAppKey());
    query.put("secret", secret);
    query.put("code", code);
    query.put("grant_type", "authorization_code");
    return parseToken(gateway.oauthGet("/oauth2/access_token", query), clock.instant(), null);
  }

  /** Persists service authorization only after the official openid matches the CRM shop. */
  public void completeServiceAuthorization(
      long tenantId, long shopId, long operatorId, WeidianTokenPayload payload) {
    TenantScope.runCompany(
        tenantId,
        () ->
            withShopLock(
                shopId,
                () -> {
                  ShopAccessDTO shop = requireEnabledShop(shopId);
                  if (payload.openid() == null || !payload.openid().equals(shop.weidianShopId())) {
                    throw new WeidianShopMismatchException();
                  }
                  WeidianShopAuthDO auth = findAuth(shopId);
                  if (auth == null) {
                    auth = new WeidianShopAuthDO();
                    auth.setShopId(shopId);
                    auth.setTenantId(tenantId);
                    auth.setCreateBy(operatorId);
                    auth.setCreateTime(clock.instant());
                  }
                  auth.setAuthMode(WeidianAuthMode.SERVICE.name());
                  auth.setSelfAppKey(null);
                  auth.setSelfAppSecretCipher(null);
                  auth.setAuthorizedBy(operatorId);
                  auth.setAuthorizedAt(clock.instant());
                  auth.setOpenid(payload.openid());
                  applyPayload(auth, payload, clock.instant(), true);
                  auth.setLastRefreshAt(clock.instant());
                  auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
                  auth.setLastError(null);
                  save(auth);
                  return null;
                }));
  }

  /** Called by the scheduled job, under each active company's tenant scope. */
  public void renewIfDue(long shopId) {
    withShopLock(
        shopId,
        () -> {
          WeidianShopAuthDO auth = findAuth(shopId);
          if (auth == null || !WeidianAuthStatus.AUTHORIZED.name().equals(auth.getStatus())) {
            return null;
          }
          if (needsRenewal(auth)) {
            try {
              renewLocked(auth);
            } catch (RuntimeException e) {
              LOG.error("Weidian token renewal failed for shop {}", shopId);
            }
          }
          return null;
        });
  }

  /** One-time passive renewal when an API reports an invalid access token. */
  public WeidianToken refreshAfterInvalid(long shopId, String rejectedToken) {
    return withShopLock(
        shopId,
        () -> {
          WeidianShopAuthDO auth = requireAuth(shopId);
          requireAuthorized(auth);
          String currentToken = cipher.decrypt(auth.getAccessTokenCipher());
          if (!currentToken.equals(rejectedToken)) {
            return token(auth);
          }
          String debounceKey = "weidian:token:passive:" + shopId;
          Boolean allowed =
              redis.opsForValue().setIfAbsent(debounceKey, "1", PASSIVE_REFRESH_WINDOW);
          if (!Boolean.TRUE.equals(allowed)) {
            throw new WeidianApiException(
                10013, "Weidian access token refresh is in cooldown", false);
          }
          try {
            renewLocked(auth);
          } catch (WeidianApiException e) {
            throw e;
          }
          return token(requireAuth(shopId));
        });
  }

  public void markExpired(long shopId, String reason) {
    WeidianShopAuthDO auth = requireAuth(shopId);
    markExpired(auth, reason);
  }

  public WeidianShopAuthDO requireAuth(long shopId) {
    ShopAccessDTO shop = shops.findShop(shopId);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    WeidianShopAuthDO auth = findAuth(shopId);
    if (auth == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    return auth;
  }

  private WeidianShopAuthDO findAuth(long shopId) {
    return auths.selectOne(
        new LambdaQueryWrapper<WeidianShopAuthDO>().eq(WeidianShopAuthDO::getShopId, shopId));
  }

  private WeidianAppDO requireApp() {
    WeidianAppDO app = apps.selectOne(new LambdaQueryWrapper<WeidianAppDO>());
    if (app == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_APP_NOT_CONFIGURED);
    }
    return app;
  }

  private ShopAccessDTO requireEnabledShop(long shopId) {
    ShopAccessDTO shop = shops.findShop(shopId);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    if (!Integer.valueOf(1).equals(shop.status())) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
    return shop;
  }

  private void renewLocked(WeidianShopAuthDO auth) {
    try {
      WeidianTokenPayload payload;
      if (WeidianAuthMode.SELF_USE.name().equals(auth.getAuthMode())) {
        payload = obtainSelfToken(auth);
      } else {
        if (auth.getRefreshTokenCipher() == null
            || (auth.getRefreshExpiresAt() != null
                && !clock.instant().isBefore(auth.getRefreshExpiresAt()))) {
          markExpired(auth, "Weidian refresh token expired");
          throw new WeidianApiException(10023, "Weidian refresh token expired", false);
        }
        WeidianAppDO app = requireApp();
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("appkey", app.getAppKey());
        query.put("grant_type", "refresh_token");
        query.put("refresh_token", cipher.decrypt(auth.getRefreshTokenCipher()));
        payload =
            parseToken(
                gateway.oauthGet("/oauth2/refresh_token", query),
                clock.instant(),
                auth.getRefreshExpiresAt());
        if (payload.openid() != null && !payload.openid().equals(auth.getOpenid())) {
          throw new WeidianShopMismatchException();
        }
      }
      applyPayload(auth, payload, clock.instant(), false);
      auth.setLastRefreshAt(clock.instant());
      auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
      auth.setLastError(null);
      save(auth);
    } catch (WeidianApiException e) {
      if (e.statusCode() == 10023) {
        markExpired(auth, "Weidian refresh token error 10023");
      } else if (e.statusCode() == 10021 || e.statusCode() == 10026) {
        markExpired(auth, safeError(e));
      } else {
        markFailed(auth, safeError(e));
      }
      throw e;
    } catch (RuntimeException e) {
      markFailed(auth, "Weidian token renewal failed");
      throw e;
    }
  }

  private WeidianTokenPayload obtainSelfToken(WeidianShopAuthDO auth) {
    LinkedHashMap<String, String> query = new LinkedHashMap<>();
    query.put("grant_type", "client_credential");
    query.put("appkey", auth.getSelfAppKey());
    query.put("secret", cipher.decrypt(auth.getSelfAppSecretCipher()));
    return parseToken(gateway.oauthGet("/token", query), clock.instant(), null);
  }

  private WeidianTokenPayload parseToken(
      JsonNode envelope, Instant acquiredAt, Instant existingRefreshExpiry) {
    JsonNode result = WeidianResponseParser.resultOrThrow(envelope);
    String access = text(result, "access_token");
    if (access == null || access.isBlank()) {
      throw new WeidianApiException(-1, "Weidian token response is incomplete", false);
    }
    long lifetime = longValue(result, "expire_in", "expires_in");
    if (lifetime <= 0) {
      throw new WeidianApiException(-1, "Weidian token response is incomplete", false);
    }
    String refresh = text(result, "refresh_token");
    Instant refreshExpiry = existingRefreshExpiry;
    JsonNode refreshTime = result.get("refreshExpireTime");
    if (refreshTime != null && !refreshTime.isNull()) {
      refreshExpiry = refreshExpiry(refreshTime, acquiredAt);
    } else if (existingRefreshExpiry == null && refresh != null) {
      refreshExpiry = acquiredAt.plus(Duration.ofDays(30));
    }
    return new WeidianTokenPayload(
        access, refresh, acquiredAt.plusSeconds(lifetime), refreshExpiry, text(result, "openid"));
  }

  static Instant refreshExpiry(JsonNode value, Instant authorizedAt) {
    long raw;
    try {
      raw = Long.parseLong(value.asText());
    } catch (NumberFormatException e) {
      return authorizedAt.plus(Duration.ofDays(30));
    }
    Instant expiry =
        raw >= 1_000_000_000_000L
            ? Instant.ofEpochMilli(raw)
            : authorizedAt.plusMillis(Math.max(0, raw));
    Instant max = authorizedAt.plus(Duration.ofDays(30));
    return expiry.isAfter(max) ? max : expiry;
  }

  private void applyPayload(
      WeidianShopAuthDO auth, WeidianTokenPayload payload, Instant at, boolean initial) {
    auth.setAccessTokenCipher(cipher.encrypt(payload.accessToken()));
    auth.setAccessExpiresAt(payload.accessExpiresAt());
    if (payload.refreshToken() != null) {
      auth.setRefreshTokenCipher(cipher.encrypt(payload.refreshToken()));
    }
    if (initial || payload.refreshExpiresAt() != null) {
      auth.setRefreshExpiresAt(payload.refreshExpiresAt());
    }
    if (payload.openid() != null) {
      auth.setOpenid(payload.openid());
    }
    auth.setLastError(null);
  }

  private void recordConnectivity(
      WeidianShopAuthDO auth, WeidianConnectivityChecker.CheckResult result) {
    auth.setLastCheckAt(clock.instant());
    auth.setLastCheckOk(true);
    auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
    auth.setLastError(
        result.sellerMatches() ? null : "WARN: Weidian seller_id differs from CRM shop ID");
    save(auth);
  }

  private void markFailed(WeidianShopAuthDO auth, String message) {
    auth.setStatus(WeidianAuthStatus.FAILED.name());
    auth.setLastError(message);
    save(auth);
  }

  private void markExpired(WeidianShopAuthDO auth, String message) {
    auth.setStatus(WeidianAuthStatus.EXPIRED.name());
    auth.setLastError(message);
    save(auth);
  }

  private static String safeError(WeidianApiException error) {
    return error.statusCode() < 0
        ? "Weidian token operation failed"
        : "Weidian error " + error.statusCode();
  }

  private boolean needsRenewal(WeidianShopAuthDO auth) {
    return auth.getAccessExpiresAt() == null
        || !clock.instant().plus(RENEW_BEFORE).isBefore(auth.getAccessExpiresAt());
  }

  private WeidianToken token(WeidianShopAuthDO auth) {
    if (auth.getAccessTokenCipher() == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    String appKey =
        WeidianAuthMode.SELF_USE.name().equals(auth.getAuthMode())
            ? auth.getSelfAppKey()
            : requireApp().getAppKey();
    return new WeidianToken(appKey, cipher.decrypt(auth.getAccessTokenCipher()));
  }

  private void requireAuthorized(WeidianShopAuthDO auth) {
    if (!WeidianAuthStatus.AUTHORIZED.name().equals(auth.getStatus())) {
      throw new BusinessException(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
  }

  private long currentTenant() {
    Long tenant = TenantScope.companyId();
    if (tenant == null) {
      tenant = current.user().tenantId();
    }
    if (tenant == null) {
      throw new IllegalStateException("Weidian shop setup requires a company");
    }
    return tenant;
  }

  private void save(WeidianShopAuthDO auth) {
    if (auth.getId() == null) {
      auths.insert(auth);
    } else {
      auths.updateById(auth);
    }
  }

  private <T> T withShopLock(long shopId, Supplier<T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    boolean acquired;
    try {
      acquired = locks.run(LOCK_PREFIX + shopId, 10, -1, () -> result.set(action.get()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WeidianTransportException(false);
    }
    if (!acquired) {
      throw new WeidianTransportException(true);
    }
    return result.get();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static long longValue(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && !value.isNull()) {
        try {
          return new BigDecimal(value.asText()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
          throw new WeidianApiException(-1, "Weidian token lifetime is invalid", false);
        }
      }
    }
    return 0;
  }
}
