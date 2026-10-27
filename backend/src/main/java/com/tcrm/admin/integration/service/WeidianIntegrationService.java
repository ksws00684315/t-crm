package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianAppRequest;
import com.tcrm.admin.integration.api.WeidianAppView;
import com.tcrm.admin.integration.api.WeidianAuthorizeView;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianSelfUseRequest;
import com.tcrm.admin.integration.api.WeidianShopView;
import com.tcrm.admin.integration.mapper.WeidianAppMapper;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianAppDO;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.product.api.ShopIntegrationApi;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WeidianIntegrationService implements ShopIntegrationApi {
  private static final Logger LOG = LoggerFactory.getLogger(WeidianIntegrationService.class);
  private static final String STATE_PREFIX = "weidian:oauth:state:";
  private static final Duration STATE_TTL = Duration.ofMinutes(10);
  private static final String CALLBACK_PATH = "/admin-api/integration/weidian/oauth/callback";
  private static final String STATE_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final Pattern VALID_STATE = Pattern.compile("[A-Za-z0-9]{32}");

  private final WeidianAppMapper apps;
  private final WeidianShopAuthMapper auths;
  private final ShopAccessApi shops;
  private final CurrentUserContext current;
  private final SecretCipher cipher;
  private final WeidianTokenCenter tokens;
  private final WeidianConnectivityChecker connectivity;
  private final WeidianBusinessApi business;
  private final WeidianAdminBaseUrl adminBaseUrl;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final ExternalAuditApi audit;
  private final String oauthBaseUrl;
  private final SecureRandom random = new SecureRandom();

  public WeidianIntegrationService(
      WeidianAppMapper apps,
      WeidianShopAuthMapper auths,
      ShopAccessApi shops,
      CurrentUserContext current,
      SecretCipher cipher,
      WeidianTokenCenter tokens,
      WeidianConnectivityChecker connectivity,
      WeidianBusinessApi business,
      WeidianAdminBaseUrl adminBaseUrl,
      StringRedisTemplate redis,
      ObjectMapper json,
      ExternalAuditApi audit,
      @Value("${crm.weidian.oauth-base-url}") String oauthBaseUrl) {
    this.apps = apps;
    this.auths = auths;
    this.shops = shops;
    this.current = current;
    this.cipher = cipher;
    this.tokens = tokens;
    this.connectivity = connectivity;
    this.business = business;
    this.adminBaseUrl = adminBaseUrl;
    this.redis = redis;
    this.json = json;
    this.audit = audit;
    this.oauthBaseUrl = oauthBaseUrl;
  }

  public WeidianAppView app() {
    WeidianAppDO app = findApp();
    return app == null
        ? new WeidianAppView(null, false, null, adminBaseUrl.callbackUrl())
        : new WeidianAppView(
            app.getAppKey(),
            app.getAppSecretCipher() != null,
            app.getUpdateTime(),
            adminBaseUrl.callbackUrl());
  }

  public void saveApp(WeidianAppRequest request) {
    if (request.appKey() == null
        || request.appKey().isBlank()
        || request.appSecret() == null
        || request.appSecret().isBlank()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var user = current.user();
    WeidianAppDO app = findApp();
    if (app == null) {
      app = new WeidianAppDO();
      app.setTenantId(user.tenantId());
      app.setCreateBy(user.id());
      app.setCreateTime(Instant.now());
    }
    app.setAppKey(request.appKey().strip());
    app.setAppSecretCipher(cipher.encrypt(request.appSecret()));
    app.setUpdateBy(user.id());
    app.setUpdateTime(Instant.now());
    if (app.getId() == null) {
      apps.insert(app);
    } else {
      apps.updateById(app);
    }
  }

  public List<WeidianShopView> shops() {
    Map<Long, WeidianShopAuthDO> rows = new HashMap<>();
    auths
        .selectList(new LambdaQueryWrapper<WeidianShopAuthDO>())
        .forEach(row -> rows.put(row.getShopId(), row));
    return shops.listShops().stream().map(shop -> shopView(shop, rows.get(shop.id()))).toList();
  }

  @Override
  public Map<Long, ShopAuthorizationDTO> statuses(java.util.Collection<Long> shopIds) {
    if (shopIds == null || shopIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, ShopAuthorizationDTO> result = new HashMap<>();
    for (WeidianShopAuthDO auth :
        auths.selectList(
            new LambdaQueryWrapper<WeidianShopAuthDO>()
                .in(WeidianShopAuthDO::getShopId, shopIds))) {
      String status = auth.getStatus();
      if (auth.getAccessExpiresAt() != null && !auth.getAccessExpiresAt().isAfter(Instant.now())) {
        status = WeidianAuthStatus.EXPIRED.name();
      }
      result.put(auth.getShopId(), new ShopAuthorizationDTO(status, auth.getAccessExpiresAt()));
    }
    return result;
  }

  @Override
  public ShopApiChecksDTO checkApis(long shopId) {
    return new ShopApiChecksDTO(
        probe(
            () -> {
              tokens.accessToken(shopId);
              return business.itemList(
                  shopId, new WeidianBusinessApi.ItemListRequest(null, "1", null, 1, null, "1"));
            }),
        probe(
            () -> {
              tokens.accessToken(shopId);
              return business.orderList(
                  shopId,
                  new WeidianBusinessApi.OrderListRequest(
                      1, false, null, "all", 1, null, null, null, null, "0", null));
            }),
        probe(
            () -> {
              tokens.accessToken(shopId);
              return business.refundList(
                  shopId,
                  new WeidianBusinessApi.RefundListRequest(
                      null, null, null, null, null, 0, 1, null, null, null));
            }));
  }

  @Override
  public int countOnSaleItems(long shopId) {
    tokens.accessToken(shopId);
    int count = 0;
    int page = 1;
    while (page <= 1000) {
      WeidianBusinessApi.ItemListResponse response =
          business.itemList(
              shopId,
              new WeidianBusinessApi.ItemListRequest(
                  null, Integer.toString(page), null, 1, null, "100"));
      if (response.totalNum() != null) {
        return Math.max(0, response.totalNum());
      }
      int current = response.items() == null ? 0 : response.items().size();
      count += current;
      if (current < 100) {
        return count;
      }
      page++;
    }
    throw new BusinessException(ErrorCode.WEIDIAN_API_FAILED);
  }

  private ShopApiCheckDTO probe(Supplier<?> request) {
    Instant checkedAt = Instant.now();
    try {
      request.get();
      return new ShopApiCheckDTO(true, checkedAt, null);
    } catch (RuntimeException error) {
      String code =
          error instanceof BusinessException businessError
              ? businessError.getErrorCode().name()
              : "WEIDIAN_API_FAILED";
      return new ShopApiCheckDTO(false, checkedAt, code);
    }
  }

  public WeidianShopView configureSelfUse(long shopId, WeidianSelfUseRequest request) {
    tokens.configureSelfUse(shopId, request.appKey(), request.appSecret());
    return shop(shopId);
  }

  public WeidianAuthorizeView authorizeServiceShop(long shopId) {
    ShopAccessDTO shop = requireEnabledShop(shopId);
    WeidianAppDO app = findApp();
    if (app == null) {
      throw new BusinessException(ErrorCode.WEIDIAN_APP_NOT_CONFIGURED);
    }
    var user = current.user();
    String state = newState();
    OAuthState saved = new OAuthState(user.tenantId(), shopId, user.id());
    try {
      redis.opsForValue().set(STATE_PREFIX + state, json.writeValueAsString(saved), STATE_TTL);
    } catch (JacksonException e) {
      throw new IllegalStateException("Unable to create Weidian authorization state");
    }
    String authorizeUrl =
        UriComponentsBuilder.fromUriString(oauthBaseUrl + "/oauth2/authorize")
            .queryParam("appkey", app.getAppKey())
            .queryParam("redirect_uri", adminBaseUrl.callbackUrl())
            .queryParam("response_type", "code")
            .queryParam("state", state)
            .build()
            .encode()
            .toUriString();
    return new WeidianAuthorizeView(authorizeUrl);
  }

  /** Consumes OAuth state once and returns only a fixed redirect reason, never provider content. */
  public CallbackResult callback(String code, String state) {
    if (state == null || !VALID_STATE.matcher(state).matches()) {
      return CallbackResult.failure("STATE_INVALID");
    }
    String rawState = redis.opsForValue().getAndDelete(STATE_PREFIX + state);
    if (rawState == null) {
      return CallbackResult.failure("STATE_INVALID");
    }
    OAuthState saved;
    try {
      saved = json.readValue(rawState, OAuthState.class);
    } catch (JacksonException e) {
      return CallbackResult.failure("STATE_INVALID");
    }
    if (saved.tenantId() == null || saved.shopId() == null || saved.userId() == null) {
      return CallbackResult.failure("STATE_INVALID");
    }
    if (code == null || code.isBlank()) {
      return CallbackResult.failure("ACCESS_DENIED");
    }
    try {
      WeidianTokenPayload token = tokens.exchangeAuthorizationCode(saved.tenantId(), code);
      tokens.completeServiceAuthorization(saved.tenantId(), saved.shopId(), saved.userId(), token);
      try {
        audit.recordTenantOperation(
            saved.tenantId(),
            saved.userId(),
            "Weidian",
            "Authorize shop",
            CALLBACK_PATH,
            "{\"shopId\":\"" + saved.shopId() + "\"}");
      } catch (RuntimeException e) {
        LOG.error("Weidian authorization audit persistence failed for shop {}", saved.shopId(), e);
      }
      return CallbackResult.ok();
    } catch (WeidianShopMismatchException e) {
      return CallbackResult.failure("SHOP_MISMATCH");
    } catch (WeidianApiException e) {
      return CallbackResult.failure("TOKEN_EXCHANGE_FAILED");
    } catch (RuntimeException e) {
      return CallbackResult.failure("TOKEN_EXCHANGE_FAILED");
    }
  }

  public WeidianShopView check(long shopId) {
    ShopAccessDTO shop = requireEnabledShop(shopId);
    WeidianShopAuthDO auth = tokens.requireAuth(shopId);
    try {
      WeidianToken token = tokens.accessToken(shopId);
      WeidianConnectivityChecker.CheckResult checked =
          connectivity.check(shop, token.appKey(), token.accessToken());
      auth = tokens.requireAuth(shopId);
      auth.setLastCheckAt(Instant.now());
      auth.setLastCheckOk(true);
      auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
      auth.setLastError(
          checked.sellerMatches() ? null : "WARN: Weidian seller_id differs from CRM shop ID");
      auths.updateById(auth);
    } catch (RuntimeException e) {
      auth = tokens.requireAuth(shopId);
      auth.setLastCheckAt(Instant.now());
      auth.setLastCheckOk(false);
      if (e instanceof WeidianApiException apiError
          && (apiError.statusCode() == 10021 || apiError.statusCode() == 10026)) {
        auth.setStatus(WeidianAuthStatus.EXPIRED.name());
        auth.setLastError("Weidian authorization expired, error " + apiError.statusCode());
      } else {
        auth.setStatus(WeidianAuthStatus.FAILED.name());
        auth.setLastError("Weidian connectivity check failed");
      }
      auths.updateById(auth);
      throw e;
    }
    return shop(shopId);
  }

  private WeidianShopView shop(long shopId) {
    ShopAccessDTO shop = requireShop(shopId);
    return shopView(shop, tokens.requireAuth(shopId));
  }

  private WeidianShopView shopView(ShopAccessDTO shop, WeidianShopAuthDO auth) {
    return auth == null
        ? new WeidianShopView(
            shop.id(),
            shop.name(),
            shop.weidianShopId(),
            null,
            WeidianAuthStatus.UNAUTHORIZED.name(),
            null,
            null,
            null,
            null,
            null,
            null,
            null)
        : new WeidianShopView(
            shop.id(),
            shop.name(),
            shop.weidianShopId(),
            auth.getAuthMode(),
            auth.getStatus(),
            auth.getAuthorizedAt(),
            auth.getAccessExpiresAt(),
            auth.getRefreshExpiresAt(),
            auth.getLastRefreshAt(),
            auth.getLastCheckAt(),
            auth.getLastCheckOk(),
            auth.getLastError());
  }

  private WeidianAppDO findApp() {
    return apps.selectOne(new LambdaQueryWrapper<WeidianAppDO>());
  }

  private ShopAccessDTO requireEnabledShop(long shopId) {
    ShopAccessDTO shop = requireShop(shopId);
    if (!Integer.valueOf(1).equals(shop.status())) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
    return shop;
  }

  private ShopAccessDTO requireShop(long shopId) {
    ShopAccessDTO shop = shops.findShop(shopId);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    return shop;
  }

  private String newState() {
    StringBuilder state = new StringBuilder(32);
    for (int i = 0; i < 32; i++) {
      state.append(STATE_ALPHABET.charAt(random.nextInt(STATE_ALPHABET.length())));
    }
    return state.toString();
  }

  public record CallbackResult(boolean success, String reason) {
    static CallbackResult ok() {
      return new CallbackResult(true, null);
    }

    static CallbackResult failure(String reason) {
      return new CallbackResult(false, reason);
    }
  }

  private record OAuthState(Long tenantId, Long shopId, Long userId) {}
}
