package com.tcrm.redirect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
                new RedirectController(redis, "https://example.com/unavailable", "unit-test-salt"))
            .build();
  }

  @Test
  void validLinkRedirectsAndAppendsHashedEvent() throws Exception {
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
    assertEquals(8, fields.size());
    assertEquals("US", fields.get("country"));
    assertEquals("KOL0001", fields.get("kolCode"));
    assertEquals("Test-Agent", fields.get("ua"));
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "unit-test-salt203.0.113.1"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(expected, fields.get("ipHash"));
  }

  @Test
  void missUsesFallback() throws Exception {
    fallback();
    verify(redis, never()).append(anyMap());
  }

  @Test
  void inactiveUsesFallback() throws Exception {
    when(redis.lookup(anyString(), anyString())).thenReturn("1|https://example.com/item|0");
    fallback();
  }

  @Test
  void invalidPathsAre404WithoutRedisLookup() throws Exception {
    for (String path :
        new String[] {"/t/BAD/P000001", "/t/KOL0001/a.b", "/t/KOL0001/" + "a".repeat(33)})
      mvc.perform(get(path)).andExpect(status().isNotFound());
    verifyNoInteractions(redis);
  }

  @Test
  void streamFailureStillRedirectsToTarget() throws Exception {
    when(redis.lookup(anyString(), anyString())).thenReturn("1|https://example.com/item|1");
    doThrow(new IllegalStateException("simulated failure")).when(redis).append(anyMap());
    mvc.perform(get("/t/KOL0001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/item"));
  }

  @Test
  void healthWorksAndRedisFailureIs503() throws Exception {
    when(redis.healthy()).thenReturn(true);
    mvc.perform(get("/healthz")).andExpect(status().isOk());
    when(redis.healthy()).thenThrow(new IllegalStateException());
    mvc.perform(get("/healthz")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void malformedAndUnsafeTargetsUseFallback() throws Exception {
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
