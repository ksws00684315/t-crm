package com.tcrm.redirect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.redirect.config.ClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RedirectServiceTest {
  private static final String ITEM =
      "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}";

  private static final ClientIpResolver IPS =
      new ClientIpResolver("10.0.0.0/8", "CF-Connecting-IP,X-Forwarded-For");

  private static final String SALT = "0123456789abcdef0123456789abcdef";

  @Test
  void shouldRejectUnsafeConfiguration_whenFallbackOrSaltIsInvalid() {
    var redis = mock(ClickRedis.class);
    for (String url :
        new String[] {
          "bad", "https:///missing-host", "ftp://example.com", "https://user:credential@example.com"
        }) {
      assertThatThrownBy(() -> new RedirectService(redis, IPS, url, SALT))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RedirectService(redis, IPS, "https://example.com", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TCRM_IP_HASH_SALT")
        .hasMessageContaining("openssl rand -hex 32");
    assertThatThrownBy(() -> new RedirectService(redis, IPS, "https://example.com", "unit-salt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TCRM_IP_HASH_SALT");
  }

  @Test
  void shouldUseFallback_whenRedisLookupFails() {
    var redis = mock(ClickRedis.class);
    when(redis.lookup(anyString(), anyString()))
        .thenThrow(new IllegalStateException("unit-failure"));
    var service = new RedirectService(redis, IPS, "https://example.com/fallback", SALT);
    assertThat(
            service
                .redirect("KOL0001", "product", new MockHttpServletRequest())
                .getHeaders()
                .getLocation()
                .toString())
        .isEqualTo("https://example.com/fallback");
    verify(redis, never()).append(anyMap());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private Map<?, ?> clickFor(MockHttpServletRequest request) {
    var redis = mock(ClickRedis.class);
    when(redis.lookup(anyString(), anyString())).thenReturn(ITEM);
    new RedirectService(redis, IPS, "https://example.com/fallback", SALT)
        .redirect("KOL0001", "product", request);
    var events = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(redis).append(events.capture());
    return events.getValue();
  }

  @Test
  void shouldHashRealClientIp_whenRequestComesThroughTrustedProxy() throws Exception {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("X-Forwarded-For", "192.0.2.77, 198.51.100.1");
    assertThat(clickFor(request).get("ipHash")).isEqualTo(sha256(SALT + "198.51.100.1"));
  }

  @Test
  void shouldHashPeerIpAndIgnoreHeaders_whenPeerIsNotTrustedProxy() throws Exception {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.2");
    request.addHeader("CF-Connecting-IP", "198.51.100.1");
    request.addHeader("X-Forwarded-For", "198.51.100.2");
    assertThat(clickFor(request).get("ipHash")).isEqualTo(sha256(SALT + "203.0.113.2"));
  }

  @Test
  void shouldTruncateOversizedHeaders_whenRecordingClick() {
    var request = new MockHttpServletRequest();
    request.addHeader("User-Agent", "a".repeat(600));
    request.addHeader("CF-IPCountry", "X".repeat(20));
    var event = clickFor(request);
    assertThat(event.get("ua").toString()).hasSize(512);
    assertThat(event.get("country").toString()).hasSize(8);
  }

  @Test
  void shouldRejectUnsafeIndexTargets_whenUriHasNoHostOrContainsUserInfo() {
    var redis = mock(ClickRedis.class);
    var service = new RedirectService(redis, IPS, "https://example.com/fallback", SALT);
    for (String target :
        new String[] {
          "https:///missing-host", "https://user:credential@example.com", "https://[invalid"
        }) {
      when(redis.lookup(anyString(), anyString()))
          .thenReturn("{\"kolItemId\":\"1\",\"targetUrl\":\"" + target + "\",\"status\":1}");
      assertThat(
              service
                  .redirect("KOL0001", "product", new MockHttpServletRequest())
                  .getHeaders()
                  .getLocation()
                  .toString())
          .isEqualTo("https://example.com/fallback");
    }
  }

  @Test
  void shouldReturnUnavailable_whenRedisHealthIsFalse() {
    var service =
        new RedirectService(mock(ClickRedis.class), IPS, "https://example.com/fallback", SALT);
    assertThat(service.health().getStatusCode().value()).isEqualTo(503);
  }

  @Test
  void shouldRedirectAndRecordItemId_whenIndexValueIsJson() {
    var redis = mock(ClickRedis.class);
    when(redis.lookup(anyString(), anyString()))
        .thenReturn(
            "{\"kolItemId\":\"77\",\"targetUrl\":\"https://example.com/p?a=1%7C2\",\"status\":1}");
    var service = new RedirectService(redis, IPS, "https://example.com/fallback", SALT);
    var response = service.redirect("KOL0001", "product", new MockHttpServletRequest());
    assertThat(response.getHeaders().getLocation().toString())
        .isEqualTo("https://example.com/p?a=1%7C2");
    var events = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(redis).append(events.capture());
    assertThat(events.getValue().get("kolItemId")).isEqualTo("77");
  }

  @Test
  void shouldUseFallbackWithoutRecording_whenIndexValueIsInactiveOrMalformed() {
    var redis = mock(ClickRedis.class);
    var service = new RedirectService(redis, IPS, "https://example.com/fallback", SALT);
    for (String value :
        new String[] {
          "{\"kolItemId\":\"77\",\"targetUrl\":\"https://example.com/p\",\"status\":0}", "{broken"
        }) {
      when(redis.lookup(anyString(), anyString())).thenReturn(value);
      assertThat(
              service
                  .redirect("KOL0001", "product", new MockHttpServletRequest())
                  .getHeaders()
                  .getLocation()
                  .toString())
          .as(value)
          .isEqualTo("https://example.com/fallback");
    }
    verify(redis, never()).append(anyMap());
  }
}
