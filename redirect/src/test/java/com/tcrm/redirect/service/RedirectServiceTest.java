package com.tcrm.redirect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RedirectServiceTest {
  @Test
  void shouldRejectUnsafeConfiguration_whenFallbackOrSaltIsInvalid() {
    var redis = mock(ClickRedis.class);
    for (String url :
        new String[] {
          "bad", "https:///missing-host", "ftp://example.com", "https://user:credential@example.com"
        }) {
      assertThatThrownBy(() -> new RedirectService(redis, url, "unit-salt"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RedirectService(redis, "https://example.com", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldUseFallback_whenRedisLookupFails() {
    var redis = mock(ClickRedis.class);
    when(redis.lookup(anyString(), anyString()))
        .thenThrow(new IllegalStateException("unit-failure"));
    var service = new RedirectService(redis, "https://example.com/fallback", "unit-salt");
    assertThat(
            service
                .redirect("KOL0001", "product", new MockHttpServletRequest())
                .getHeaders()
                .getLocation()
                .toString())
        .isEqualTo("https://example.com/fallback");
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldHashFirstForwardedOrRemoteIp_whenCloudflareHeaderIsAbsent() throws Exception {
    var redis = mock(ClickRedis.class);
    when(redis.lookup(anyString(), anyString())).thenReturn("1|https://example.com/item|1");
    var service = new RedirectService(redis, "https://example.com/fallback", "unit-salt");
    for (boolean forwarded : new boolean[] {true, false}) {
      var request = new MockHttpServletRequest();
      request.setRemoteAddr("203.0.113.2");
      if (forwarded) {
        request.addHeader("X-Forwarded-For", " 198.51.100.1 , 198.51.100.2");
      }
      request.addHeader("User-Agent", "a".repeat(600));
      request.addHeader("CF-IPCountry", "X".repeat(20));
      service.redirect("KOL0001", "product", request);
      var events = org.mockito.ArgumentCaptor.forClass(Map.class);
      verify(redis, atLeastOnce()).append(events.capture());
      Map<?, ?> event = events.getAllValues().getLast();
      String ip = forwarded ? "198.51.100.1" : "203.0.113.2";
      assertThat(event.get("ipHash"))
          .isEqualTo(
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(("unit-salt" + ip).getBytes(StandardCharsets.UTF_8))));
      assertThat(event.get("ua").toString()).hasSize(512);
      assertThat(event.get("country").toString()).hasSize(8);
    }
  }

  @Test
  void shouldRejectUnsafeIndexTargets_whenUriHasNoHostOrContainsUserInfo() {
    var redis = mock(ClickRedis.class);
    var service = new RedirectService(redis, "https://example.com/fallback", "unit-salt");
    for (String target :
        new String[] {
          "https:///missing-host", "https://user:credential@example.com", "https://[invalid"
        }) {
      when(redis.lookup(anyString(), anyString())).thenReturn("1|" + target + "|1");
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
        new RedirectService(mock(ClickRedis.class), "https://example.com/fallback", "unit-salt");
    assertThat(service.health().getStatusCode().value()).isEqualTo(503);
  }
}
