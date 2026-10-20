package com.tcrm.admin.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Platform configuration persistence, immediate cache refresh, public fields, and role checks. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SystemConfigIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    registry.add("crm.bootstrap.username", () -> "bootstrap");
    registry.add("crm.bootstrap.password", () -> PASSWORD);
    registry.add("crm.link.base-url", () -> "https://t.example.com");
    registry.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    registry.add("crm.redirect.fallback-url", () -> "https://fallback.example.com");
    registry.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(registry);
  }

  private final MockMvc mvc;
  private final ObjectMapper json;
  private final JdbcTemplate jdbc;
  private final SystemConfigApi configs;
  private final StringRedisTemplate redis;

  @Autowired
  SystemConfigIT(
      MockMvc mvc,
      ObjectMapper json,
      JdbcTemplate jdbc,
      SystemConfigApi configs,
      StringRedisTemplate redis) {
    this.mvc = mvc;
    this.json = json;
    this.jdbc = jdbc;
    this.configs = configs;
    this.redis = redis;
  }

  @Test
  void shouldPersistOverrideRefreshCacheAndRestoreDefaultImmediately() throws Exception {
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    String companyAdmin = CompanySessions.companyAdmin(mvc, json, PASSWORD);

    mvc.perform(get("/admin-api/platform/system-configs").header(TOKEN, companyAdmin))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/platform/system-configs").header(TOKEN, platform))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].items[0].key").exists());

    putConfig(platform, "site.name", "t34_System").andExpect(status().isOk());
    assertThat(configs.getValue("site.name")).isEqualTo("t34_System");
    assertThat(
            jdbc.queryForObject(
                "SELECT config_value FROM sys_config WHERE config_key = 'site.name'", String.class))
        .isEqualTo("t34_System");
    mvc.perform(get("/admin-api/public/site-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.siteName").value("t34_System"))
        .andExpect(jsonPath("$.data.uploadBaseUrl").value(""));

    mvc.perform(delete("/admin-api/platform/system-configs/site.name").header(TOKEN, platform))
        .andExpect(status().isOk());
    assertThat(configs.getValue("site.name")).isEqualTo("SparkTrack");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_config WHERE config_key = 'site.name'", Integer.class))
        .isZero();

    putConfig(platform, "url.redirect-fallback", "http://localhost:18265")
        .andExpect(status().isOk());
    assertThat(redis.opsForValue().get("crm:config:redirect-fallback-url"))
        .isEqualTo("http://localhost:18265");
    mvc.perform(
            delete("/admin-api/platform/system-configs/url.redirect-fallback")
                .header(TOKEN, platform))
        .andExpect(status().isOk());
    assertThat(redis.opsForValue().get("crm:config:redirect-fallback-url"))
        .isEqualTo("https://fallback.example.com");
  }

  @Test
  void shouldExposeOnlyThePublicContractWithoutLogin() throws Exception {
    mvc.perform(get("/admin-api/public/site-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.siteName").exists())
        .andExpect(jsonPath("$.data.uploadBaseUrl").exists())
        .andExpect(jsonPath("$.data.key").doesNotExist());
    mvc.perform(get("/kol-api/public/site-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.siteName").exists())
        .andExpect(jsonPath("$.data.uploadBaseUrl").doesNotExist());
    mvc.perform(get("/admin-api/platform/system-configs")).andExpect(status().isUnauthorized());
  }

  private org.springframework.test.web.servlet.ResultActions putConfig(
      String token, String key, String value) throws Exception {
    return mvc.perform(
        put("/admin-api/platform/system-configs/" + key)
            .header(TOKEN, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("value", value))));
  }
}
