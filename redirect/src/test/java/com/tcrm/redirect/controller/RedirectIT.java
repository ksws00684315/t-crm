package com.tcrm.redirect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RedirectIT {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("crm.redirect.fallback-url", () -> "https://example.com/unavailable");
    r.add("crm.redirect.ip-hash-salt", () -> "integration-test-salt0123456789a");
  }

  private final MockMvc mvc;
  private final StringRedisTemplate redis;

  @Autowired
  RedirectIT(MockMvc mvc, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.redis = redis;
  }

  @BeforeEach
  void clear() {
    redis.delete(java.util.List.of("link:KOL0001", "stream:click"));
  }

  @Test
  void shouldValidLinkWritesRealStream_whenRequested() throws Exception {
    link("1");
    target();
    assertThat(redis.opsForStream().size("stream:click")).isEqualTo(1L);
    var event =
        redis
            .opsForStream()
            .range("stream:click", org.springframework.data.domain.Range.unbounded())
            .getFirst()
            .getValue();
    assertThat(event.get("kolCode")).isEqualTo("KOL0001");
    assertThat(event.get("ipHash").toString().length()).isEqualTo(64);
  }

  @Test
  void shouldMissUsesFallback_whenRequested() throws Exception {
    fallback();
  }

  @Test
  void shouldInactiveUsesFallback_whenRequested() throws Exception {
    link("0");
    fallback();
  }

  @Test
  void shouldInvalidPathReturns404_whenRequested() throws Exception {
    mvc.perform(get("/t/BAD/P000001")).andExpect(status().isNotFound());
  }

  @Test
  void shouldStreamCommandDeniedStillRedirects_whenRequested() throws Exception {
    link("1");
    acl("-xadd");
    try {
      target();
      assertThat(Boolean.TRUE.equals(redis.hasKey("stream:click"))).isFalse();
    } finally {
      acl("+xadd");
    }
  }

  @Test
  void shouldHealthIs200_whenRequested() throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  private void link(String status) {
    redis
        .opsForHash()
        .put(
            "link:KOL0001",
            "P000001",
            "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":"
                + status
                + "}");
  }

  private void target() throws Exception {
    mvc.perform(get("/t/KOL0001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/item"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  private void fallback() throws Exception {
    mvc.perform(get("/t/KOL0001/P000001"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/unavailable"));
  }

  private void acl(String rule) {
    redis.execute(
        (RedisCallback<Object>)
            c ->
                c.execute(
                    "ACL",
                    "SETUSER".getBytes(StandardCharsets.UTF_8),
                    "default".getBytes(StandardCharsets.UTF_8),
                    rule.getBytes(StandardCharsets.UTF_8)));
  }
}
