package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tcrm.admin.integration.mapper.WeidianAppMapper;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianAppDO;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.SecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeidianTokenCenterTest {
  private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
  private static final long SHOP_ID = 12L;
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void shouldInterpretRefreshExpiryAsAbsoluteMilliseconds_whenValueExceedsThreshold() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(1_792_022_400_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(Instant.ofEpochMilli(1_792_022_400_000L));
  }

  @Test
  void shouldInterpretRefreshExpiryAsDuration_whenValueIsBelowThreshold() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(2_592_000_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(authorizedAt.plusMillis(2_592_000_000L));
  }

  @Test
  void shouldCapRefreshExpiryAtThirtyDays_whenAbsoluteValueExceedsLimit() {
    Instant authorizedAt = Instant.parse("2026-10-01T00:00:00Z");
    var value = JsonMapper.builder().build().valueToTree(1_900_000_000_000L);

    assertThat(WeidianTokenCenter.refreshExpiry(value, authorizedAt))
        .isEqualTo(authorizedAt.plusSeconds(30 * 24 * 60 * 60L));
  }

  @Test
  void shouldKeepAuthorizedStatusDuringPassiveRefreshCooldown() throws Exception {
    CenterFixture fixture = new CenterFixture();
    WeidianShopAuthDO auth = authorizedSelfUse(fixture.cipher, NOW.plusSeconds(3600));
    fixture.returnAuth(auth);
    when(fixture.redis.opsForValue()).thenReturn(fixture.values);
    when(fixture.values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
        .thenReturn(false);
    fixture.runLockInline();

    assertThatThrownBy(() -> fixture.center.refreshAfterInvalid(SHOP_ID, "old-access"))
        .isInstanceOf(WeidianApiException.class)
        .hasMessage("Weidian access token refresh is in cooldown");

    assertThat(auth.getStatus()).isEqualTo(WeidianAuthStatus.AUTHORIZED.name());
    verify(fixture.auths, never()).updateById(any(WeidianShopAuthDO.class));
  }

  @Test
  void shouldRenewSelfUseAndServiceTokensWhenInsideThreeHourWindow() throws Exception {
    assertRenewal(WeidianAuthMode.SELF_USE, "/token");
    assertRenewal(WeidianAuthMode.SERVICE, "/oauth2/refresh_token");
  }

  @Test
  void shouldMarkServiceAuthorizationExpiredWhenProviderRejectsRefreshToken() throws Exception {
    CenterFixture fixture = new CenterFixture();
    WeidianShopAuthDO auth = authorizedService(fixture.cipher, NOW.plusSeconds(3600));
    fixture.returnAuth(auth);
    fixture.returnApp();
    when(fixture.gateway.oauthGet(anyString(), any()))
        .thenReturn(envelope("{\"status\":{\"status_code\":10023}}"));
    fixture.runLockInline();

    fixture.center.renewIfDue(SHOP_ID);

    assertThat(auth.getStatus()).isEqualTo(WeidianAuthStatus.EXPIRED.name());
    assertThat(auth.getLastError()).doesNotContain("old-access", "old-refresh");
  }

  private void assertRenewal(WeidianAuthMode mode, String expectedPath) throws Exception {
    CenterFixture fixture = new CenterFixture();
    WeidianShopAuthDO auth =
        mode == WeidianAuthMode.SELF_USE
            ? authorizedSelfUse(fixture.cipher, NOW.plusSeconds(3600))
            : authorizedService(fixture.cipher, NOW.plusSeconds(3600));
    fixture.returnAuth(auth);
    if (mode == WeidianAuthMode.SERVICE) {
      fixture.returnApp();
    }
    when(fixture.gateway.oauthGet(anyString(), any()))
        .thenReturn(
            envelope(
                "{\"status\":{\"status_code\":0},\"result\":{"
                    + "\"access_token\":\"renewed-access\",\"expire_in\":14400,"
                    + "\"refresh_token\":\"renewed-refresh\",\"openid\":\"weidian-12\"}}"));
    fixture.runLockInline();

    fixture.center.renewIfDue(SHOP_ID);

    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    verify(fixture.gateway).oauthGet(path.capture(), any());
    assertThat(path.getValue()).isEqualTo(expectedPath);
    assertThat(auth.getStatus()).isEqualTo(WeidianAuthStatus.AUTHORIZED.name());
    assertThat(fixture.cipher.decrypt(auth.getAccessTokenCipher())).isEqualTo("renewed-access");
    verify(fixture.auths).updateById(auth);
  }

  private JsonNode envelope(String body) {
    return json.readTree(body);
  }

  private WeidianShopAuthDO authorizedSelfUse(SecretCipher cipher, Instant expiry) {
    WeidianShopAuthDO auth = new WeidianShopAuthDO();
    auth.setId(1L);
    auth.setTenantId(1L);
    auth.setShopId(SHOP_ID);
    auth.setAuthMode(WeidianAuthMode.SELF_USE.name());
    auth.setSelfAppKey("self-app");
    auth.setSelfAppSecretCipher(cipher.encrypt("self-secret"));
    auth.setAccessTokenCipher(cipher.encrypt("old-access"));
    auth.setAccessExpiresAt(expiry);
    auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
    return auth;
  }

  private WeidianShopAuthDO authorizedService(SecretCipher cipher, Instant expiry) {
    WeidianShopAuthDO auth = new WeidianShopAuthDO();
    auth.setId(2L);
    auth.setTenantId(1L);
    auth.setShopId(SHOP_ID);
    auth.setAuthMode(WeidianAuthMode.SERVICE.name());
    auth.setOpenid("weidian-12");
    auth.setAccessTokenCipher(cipher.encrypt("old-access"));
    auth.setRefreshTokenCipher(cipher.encrypt("old-refresh"));
    auth.setRefreshExpiresAt(NOW.plusSeconds(TimeUnit.DAYS.toSeconds(10)));
    auth.setAccessExpiresAt(expiry);
    auth.setStatus(WeidianAuthStatus.AUTHORIZED.name());
    return auth;
  }

  private final class CenterFixture {
    private final WeidianShopAuthMapper auths = mock(WeidianShopAuthMapper.class);
    private final WeidianAppMapper apps = mock(WeidianAppMapper.class);
    private final ShopAccessApi shops = mock(ShopAccessApi.class);
    private final CurrentUserContext current = mock(CurrentUserContext.class);
    private final SecretCipher cipher = new SecretCipher(randomSecretKey());
    private final WeidianHttpGateway gateway = mock(WeidianHttpGateway.class);
    private final WeidianRateLimiter limiter = mock(WeidianRateLimiter.class);
    private final WeidianConnectivityChecker connectivity = mock(WeidianConnectivityChecker.class);
    private final DistributedLocks locks = mock(DistributedLocks.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final WeidianTokenCenter center =
        new WeidianTokenCenter(
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
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    private CenterFixture() {
      when(shops.findShop(SHOP_ID))
          .thenReturn(new ShopAccessDTO(SHOP_ID, "Test shop", "weidian-12", 1));
      when(redis.opsForValue()).thenReturn(values);
    }

    private void returnAuth(WeidianShopAuthDO auth) {
      when(auths.selectOne(any(Wrapper.class))).thenReturn(auth);
    }

    private void returnApp() {
      WeidianAppDO app = new WeidianAppDO();
      app.setId(4L);
      app.setTenantId(1L);
      app.setAppKey("service-app");
      app.setAppSecretCipher(cipher.encrypt("service-secret"));
      when(apps.selectOne(any(Wrapper.class))).thenReturn(app);
    }

    private void runLockInline() throws InterruptedException {
      doAnswer(
              invocation -> {
                ((Runnable) invocation.getArgument(3)).run();
                return true;
              })
          .when(locks)
          .run(anyString(), anyLong(), anyLong(), any(Runnable.class));
    }
  }

  private static String randomSecretKey() {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    return java.util.Base64.getEncoder().encodeToString(key);
  }
}
