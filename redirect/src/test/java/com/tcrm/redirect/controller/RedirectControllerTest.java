package com.tcrm.redirect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.redirect.service.ClickRedis;
import com.tcrm.redirect.service.RedirectService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RedirectControllerTest {
  private ClickRedis redis;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    redis = mock(ClickRedis.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RedirectController(
                    new RedirectService(
                        redis, "https://example.com/unavailable", "unit-test-salt")))
            .build();
  }

  @Test
  void shouldValidLinkRedirectsAndAppendsHashedEvent_whenRequested() throws Exception {
    when(redis.lookup("KOL0001", "P000001")).thenReturn("1|https://example.com/item|1");
    mvc.perform(
            get("/t/KOL0001/P000001")
                .header("CF-Connecting-IP", "203.0.113.1")
                .header("X-Forwarded-For", "198.51.100.1")
                .header("CF-IPCountry", "US")
                .header("User-Agent", "Test-Agent")
                .header("Referer", "https://example.com/source"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/item"))
        .andExpect(header().string("Cache-Control", "no-store"));
    var event = ArgumentCaptor.forClass(Map.class);
    verify(redis).append(event.capture());
    Map<?, ?> fields = event.getValue();
    assertThat(fields.size()).isEqualTo(8);
    assertThat(fields.get("country")).isEqualTo("US");
    assertThat(fields.get("kolCode")).isEqualTo("KOL0001");
    assertThat(fields.get("ua")).isEqualTo("Test-Agent");
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "unit-test-salt203.0.113.1"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(fields.get("ipHash")).isEqualTo(expected);
  }

  @Test
  void shouldMissUsesFallback_whenRequested() throws Exception {
    fallback();
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldInactiveUsesFallback_whenRequested() throws Exception {
    when(redis.lookup(anyString(), anyString())).thenReturn("1|https://example.com/item|0");
    fallback();
  }

  @Test
  void shouldInvalidPathsAre404WithoutRedisLookup_whenRequested() throws Exception {
    for (String path :
        new String[] {"/t/BAD/P000001", "/t/KOL0001/a.b", "/t/KOL0001/" + "a".repeat(33)})
      mvc.perform(get(path)).andExpect(status().isNotFound());
    verifyNoInteractions(redis);
  }

  @Test
  void shouldStreamFailureStillRedirectsToTarget_whenRequested() throws Exception {
    when(redis.lookup(anyString(), anyString())).thenReturn("1|https://example.com/item|1");
    doThrow(new IllegalStateException("simulated failure")).when(redis).append(anyMap());
    mvc.perform(get("/t/KOL0001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/item"));
  }

  @Test
  void shouldReturnHealthy_whenRedisIsAvailable() throws Exception {
    when(redis.healthy()).thenReturn(true);
    mvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  @Test
  void shouldReturnUnavailable_whenRedisHealthThrows() throws Exception {
    when(redis.healthy()).thenThrow(new IllegalStateException());
    mvc.perform(get("/healthz")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void shouldMalformedAndUnsafeTargetsUseFallback_whenRequested() throws Exception {
    for (String value :
        new String[] {
          "bad",
          "1|javascript:alert(1)|1",
          "1|https://example.com|1|extra",
          "|https://example.com|1"
        }) {
      when(redis.lookup(anyString(), anyString())).thenReturn(value);
      fallback();
    }
    verify(redis, never()).append(anyMap());
  }

  private void fallback() throws Exception {
    mvc.perform(get("/t/KOL0001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/unavailable"));
  }
}
