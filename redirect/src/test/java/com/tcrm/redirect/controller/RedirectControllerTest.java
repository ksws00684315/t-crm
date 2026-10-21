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

import com.tcrm.redirect.config.ClientIpResolver;
import com.tcrm.redirect.service.ClickRedis;
import com.tcrm.redirect.service.RedirectService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RedirectControllerTest {
  private static final String ITEM =
      "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}";

  private ClickRedis redis;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    redis = mock(ClickRedis.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RedirectController(
                    new RedirectService(
                        redis,
                        new ClientIpResolver("127.0.0.1", "CF-Connecting-IP,X-Forwarded-For"),
                        "https://example.com",
                        "unit-test-salt0123456789abcdef01")))
            .build();
  }

  @Test
  void shouldValidLinkRedirectsAndAppendsHashedEvent_whenRequested() throws Exception {
    when(redis.lookup("KOL-AAA001", "P000001")).thenReturn(ITEM);
    mvc.perform(
            get("/t/KOL-AAA001/P000001")
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
    assertThat(fields.get("kolCode")).isEqualTo("KOL-AAA001");
    assertThat(fields.get("ua")).isEqualTo("Test-Agent");
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "unit-test-salt0123456789abcdef01203.0.113.1"
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
    when(redis.lookup(anyString(), anyString()))
        .thenReturn(
            "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":0}");
    fallback();
  }

  @Test
  void shouldInvalidPathsAre404WithoutRedisLookup_whenRequested() throws Exception {
    for (String path :
        new String[] {"/t/BAD/P000001", "/t/KOL-AAA001/a.b", "/t/KOL-AAA001/" + "a".repeat(33)}) {
      mvc.perform(get(path)).andExpect(status().isNotFound());
    }
    verifyNoInteractions(redis);
  }

  @Test
  void shouldStreamFailureStillRedirectsToTarget_whenRequested() throws Exception {
    when(redis.lookup(anyString(), anyString())).thenReturn(ITEM);
    doThrow(new IllegalStateException("simulated failure")).when(redis).append(anyMap());
    mvc.perform(get("/t/KOL-AAA001/P000001"))
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
          "{\"kolItemId\":\"1\",\"targetUrl\":\"javascript:alert(1)\",\"status\":1}",
          "{\"kolItemId\":\"\",\"targetUrl\":\"https://example.com\",\"status\":1}",
          "1|https://example.com/item|1"
        }) {
      when(redis.lookup(anyString(), anyString())).thenReturn(value);
      fallback();
    }
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldItemLinkRedirectAndAppendHashedEvent_whenActive() throws Exception {
    when(redis.lookupItem("7849640325")).thenReturn(ITEM);
    mvc.perform(
            get("/i/7849640325")
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
    assertThat(fields.keySet().stream().map(String::valueOf).toList())
        .containsExactlyInAnyOrder("kolItemId", "ts", "ipHash", "ua", "ref", "country");
    assertThat(fields.get("kolItemId")).isEqualTo("1");
    assertThat(fields.get("country")).isEqualTo("US");
    assertThat(fields.get("ua")).isEqualTo("Test-Agent");
    assertThat(fields.get("ref")).isEqualTo("https://example.com/source");
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "unit-test-salt0123456789abcdef01203.0.113.1"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(fields.get("ipHash")).isEqualTo(expected);
  }

  @Test
  void shouldItemMissUseFallbackWithoutEvent_whenNotIndexed() throws Exception {
    itemFallback("/i/7849640325");
    verify(redis).lookupItem("7849640325");
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldItemUseFallbackWithoutEvent_whenInactive() throws Exception {
    when(redis.lookupItem(anyString()))
        .thenReturn(
            "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":0}");
    itemFallback("/i/7849640325");
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldItemUseFallbackWithoutEvent_whenValueIsMalformedOrUnsafe() throws Exception {
    for (String value :
        new String[] {
          "bad",
          "{\"kolItemId\":\"1\",\"targetUrl\":\"javascript:alert(1)\",\"status\":1}",
          "{\"kolItemId\":\"\",\"targetUrl\":\"https://example.com\",\"status\":1}",
          "1|https://example.com/item|1"
        }) {
      when(redis.lookupItem(anyString())).thenReturn(value);
      itemFallback("/i/7849640325");
    }
    verify(redis, never()).append(anyMap());
  }

  @Test
  void shouldInvalidItemIdUseFallbackWithoutRedisLookup_whenRequested() throws Exception {
    for (String path :
        new String[] {"/i/abc", "/i/12a", "/i/" + "1".repeat(21), "/i/1.0", "/i/%D9%A1"}) {
      itemFallback(path);
    }
    verify(redis).getValue("crm:config:redirect-fallback-url");
    verify(redis, never()).lookupItem(anyString());
  }

  @Test
  void shouldItemStreamFailureStillRedirectToTarget_whenRequested() throws Exception {
    when(redis.lookupItem(anyString())).thenReturn(ITEM);
    doThrow(new IllegalStateException("simulated failure")).when(redis).append(anyMap());
    mvc.perform(get("/i/7849640325"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/item"));
  }

  private void itemFallback(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  private void fallback() throws Exception {
    mvc.perform(get("/t/KOL-AAA001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com"));
  }
}
