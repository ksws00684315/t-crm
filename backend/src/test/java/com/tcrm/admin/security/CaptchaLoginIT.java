package com.tcrm.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.Captchas;
import com.tcrm.admin.WeidianTestProperties;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Login captcha with the default configuration (enabled), against real Redis. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CaptchaLoginIT {
  private static final String PASSWORD = UUID.randomUUID().toString();

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
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("crm.bootstrap.username", () -> "bootstrap");
    r.add("crm.bootstrap.password", () -> PASSWORD);
    r.add("crm.link.base-url", () -> "https://t.example.com");
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;

  @Autowired
  CaptchaLoginIT(MockMvc mvc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.json = json;
    this.redis = redis;
  }

  @Test
  void shouldIssuePublicPngCaptchaStoredWithShortTtl() throws Exception {
    String body =
        mvc.perform(post("/admin-api/auth/captchas"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.expiresIn").value(180))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var data = json.readTree(body).at("/data");
    String image = data.get("image").asText();
    assertThat(image).startsWith("data:image/png;base64,");
    var png =
        ImageIO.read(
            new ByteArrayInputStream(
                Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1))));
    assertThat(png.getWidth()).isEqualTo(120);
    String key = "auth:captcha:" + data.get("captchaId").asText();
    assertThat(redis.opsForValue().get(key)).hasSize(4);
    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(170L, 180L);
  }

  @Test
  void shouldLogIn_withCorrectCaptchaInAnyCase() throws Exception {
    var captcha = Captchas.solve(mvc, json);
    login("192.0.2.10", body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").isNotEmpty());
    var upper = Captchas.solve(mvc, json);
    login(
            "192.0.2.10",
            body("bootstrap", PASSWORD, upper.captchaId(), upper.captchaCode().toUpperCase()))
        .andExpect(status().isOk());
  }

  @Test
  void shouldRejectMissingCaptcha() throws Exception {
    expectCaptchaInvalid(login("192.0.2.11", body("bootstrap", PASSWORD, null, null)));
    expectCaptchaInvalid(
        login(
            "192.0.2.11",
            body("bootstrap", PASSWORD, Captchas.solve(mvc, json).captchaId(), null)));
    expectCaptchaInvalid(login("192.0.2.11", body("bootstrap", PASSWORD, null, "AB7K")));
  }

  @Test
  void shouldConsumeCaptcha_whenAnswerIsWrong() throws Exception {
    var captcha = Captchas.solve(mvc, json);
    expectCaptchaInvalid(
        login("192.0.2.12", body("bootstrap", PASSWORD, captcha.captchaId(), "wrong")));
    assertThat(redis.hasKey("auth:captcha:" + captcha.captchaId())).isFalse();
    // The right answer no longer helps: one image allows one guess.
    expectCaptchaInvalid(
        login(
            "192.0.2.12", body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode())));
  }

  @Test
  void shouldRejectReusedCaptcha_afterSuccessfulLogin() throws Exception {
    var captcha = Captchas.solve(mvc, json);
    Map<String, String> request =
        body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode());
    login("192.0.2.13", request).andExpect(status().isOk());
    expectCaptchaInvalid(login("192.0.2.13", request));
  }

  @Test
  void shouldRejectExpiredCaptcha() throws Exception {
    var captcha = Captchas.solve(mvc, json);
    String key = "auth:captcha:" + captcha.captchaId();
    redis.expire(key, java.time.Duration.ofMillis(1));
    Thread.sleep(20);
    assertThat(redis.hasKey(key)).isFalse();
    expectCaptchaInvalid(
        login(
            "192.0.2.14", body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode())));
  }

  @Test
  void shouldNotCountTowardLockout_whenCaptchaIsWrong() throws Exception {
    // Far more wrong-captcha attempts than the pair limit (5), with a wrong password too.
    for (int i = 0; i < 8; i++) {
      var captcha = Captchas.solve(mvc, json);
      expectCaptchaInvalid(
          login("192.0.2.15", body("bootstrap", "wrong-password", captcha.captchaId(), "zzzz")));
    }
    assertThat(redis.keys("auth:fail:*")).allMatch(k -> !k.contains("lock"));
    var captcha = Captchas.solve(mvc, json);
    login("192.0.2.15", body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode()))
        .andExpect(status().isOk());
  }

  @Test
  void shouldKeepLockout_whenCaptchaIsCorrectButPasswordIsWrong() throws Exception {
    for (int i = 1; i <= 5; i++) {
      var captcha = Captchas.solve(mvc, json);
      var result =
          login(
              "192.0.2.16",
              body("bootstrap", "wrong-password", captcha.captchaId(), captcha.captchaCode()));
      if (i < 5) {
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
      } else {
        result
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_LOCKED"));
      }
    }
    var captcha = Captchas.solve(mvc, json);
    login("192.0.2.16", body("bootstrap", PASSWORD, captcha.captchaId(), captcha.captchaCode()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_LOCKED"));
    // A locked attempt still needs a valid captcha first, so the lock cannot be probed without one.
    expectCaptchaInvalid(login("192.0.2.16", body("bootstrap", PASSWORD, null, null)));
  }

  @Test
  void shouldRateLimitCaptchaIssuingPerClientIp() throws Exception {
    // Default configuration: 120 per IP per minute.
    for (int i = 0; i < 120; i++) {
      issueFrom("192.0.2.17").andExpect(status().isOk());
    }
    issueFrom("192.0.2.17")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("AUTH_CAPTCHA_RATE_LIMITED"));
    issueFrom("192.0.2.18").andExpect(status().isOk());
  }

  private ResultActions issueFrom(String ip) throws Exception {
    return mvc.perform(
        post("/admin-api/auth/captchas")
            .with(
                request -> {
                  request.setRemoteAddr(ip);
                  return request;
                }));
  }

  private ResultActions login(String ip, Map<String, String> body) throws Exception {
    return mvc.perform(
        post("/admin-api/auth/login")
            .with(
                request -> {
                  request.setRemoteAddr(ip);
                  return request;
                })
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private static void expectCaptchaInvalid(ResultActions result) throws Exception {
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("AUTH_CAPTCHA_INVALID"));
  }

  private static Map<String, String> body(
      String username, String password, String captchaId, String captchaCode) {
    Map<String, String> body = new HashMap<>();
    body.put("username", username);
    body.put("password", password);
    if (captchaId != null) {
      body.put("captchaId", captchaId);
    }
    if (captchaCode != null) {
      body.put("captchaCode", captchaCode);
    }
    return body;
  }
}
