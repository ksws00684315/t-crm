package com.tcrm.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.support.WebApplicationContextUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Solves login captchas in integration tests, which run with captchas enabled like production. The
 * answer is read from Redis (the image cannot be read), and the per-IP issue counter is reset so
 * test suites that log in many times from 127.0.0.1 are not rate limited; CaptchaLoginIT covers the
 * limit itself.
 */
public final class Captchas {
  private Captchas() {}

  public record Solved(String captchaId, String captchaCode) {}

  public static Solved solve(MockMvc mvc, ObjectMapper json) throws Exception {
    MvcResult result =
        mvc.perform(post("/admin-api/auth/captchas")).andExpect(status().isOk()).andReturn();
    String id =
        json.readTree(result.getResponse().getContentAsString()).at("/data/captchaId").asText();
    StringRedisTemplate redis = redis(result);
    String answer = redis.opsForValue().get("auth:captcha:" + id);
    if (answer == null) {
      throw new AssertionError("captcha " + id + " was not stored");
    }
    var rateKeys = redis.keys("auth:captcha:rate:*");
    if (!rateKeys.isEmpty()) {
      redis.delete(rateKeys);
    }
    return new Solved(id, answer.toLowerCase(Locale.ROOT));
  }

  /** Login request body with a freshly solved captcha. */
  public static Map<String, String> loginBody(
      MockMvc mvc, ObjectMapper json, String username, String password) throws Exception {
    Solved captcha = solve(mvc, json);
    return Map.of(
        "username",
        username,
        "password",
        password,
        "captchaId",
        captcha.captchaId(),
        "captchaCode",
        captcha.captchaCode());
  }

  static StringRedisTemplate redis(MvcResult result) {
    return WebApplicationContextUtils.getRequiredWebApplicationContext(
            result.getRequest().getServletContext())
        .getBean(StringRedisTemplate.class);
  }
}
