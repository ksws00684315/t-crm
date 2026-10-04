package com.tcrm.redirect;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
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
class RedirectDockerTest {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("crm.redirect.fallback-url", () -> "https://example.com/unavailable");
    r.add("crm.redirect.ip-hash-salt", () -> "integration-test-salt");
  }

  @Autowired MockMvc mvc;
  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void clear() {
    redis.delete(java.util.List.of("link:KOL0001", "stream:click"));
  }

  @Test
  void validLinkWritesRealStream() throws Exception {
    link("1");
    target();
    assertEquals(1L, redis.opsForStream().size("stream:click"));
    var event =
        redis
            .opsForStream()
            .range("stream:click", org.springframework.data.domain.Range.unbounded())
            .getFirst()
            .getValue();
    assertEquals("KOL0001", event.get("kolCode"));
    assertEquals(64, event.get("ipHash").toString().length());
  }

  @Test
  void missUsesFallback() throws Exception {
    fallback();
  }

  @Test
  void inactiveUsesFallback() throws Exception {
    link("0");
    fallback();
  }

  @Test
  void invalidPathReturns404() throws Exception {
    mvc.perform(get("/t/BAD/P000001")).andExpect(status().isNotFound());
  }

  @Test
  void streamCommandDeniedStillRedirects() throws Exception {
    link("1");
    acl("-xadd");
    try {
      target();
      assertFalse(Boolean.TRUE.equals(redis.hasKey("stream:click")));
    } finally {
      acl("+xadd");
    }
  }

  @Test
  void healthIs200() throws Exception {
    mvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  private void link(String status) {
    redis.opsForHash().put("link:KOL0001", "P000001", "1|https://example.com/item|" + status);
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
