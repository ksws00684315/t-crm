package com.tcrm.admin.kol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.service.SecureTokens;
import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 12c against real PostgreSQL and Redis: a BD's register link, public self-registration, the
 * email-code rules and the account status exposed to the admin KOL API.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolRegisterIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN = "X-Admin-Token";
  private static final String KOL = "X-Kol-Token";
  private static final Pattern DIGITS = Pattern.compile("(?m)^(\\d{6})$");
  private static final String REGISTER_PASSWORD = "register-pass-1";

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
    registry.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(registry);
  }

  /** Captures outgoing mail. The registration code is read from the message, not from a log. */
  @TestConfiguration
  static class CapturedMails {
    static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    MailSender capturingMailSender() {
      return SENT::add;
    }
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;

  @Autowired
  KolRegisterIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
  }

  @BeforeEach
  void prepareIndependentData() {
    jdbc.update("DELETE FROM crm_kol_auth_token");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_kol_register_code");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = 1");
    CapturedMails.SENT.clear();
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void shouldCreateTheRegisterCodeTable_withoutLogicalDelete() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version ="
                    + " '202610171500'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT obj_description('crm_kol_register_code'::regclass)", String.class))
        .contains("不使用逻辑删除");
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'crm_kol_register_code'",
                String.class))
        .contains(
            "uk_crm_kol_register_code_code",
            "uk_crm_kol_register_code_active_bd",
            "idx_crm_kol_register_code_bd");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name ="
                    + " 'crm_kol_register_code' AND column_name = 'is_deleted'",
                Integer.class))
        .isZero();
  }

  @Test
  void shouldIssueOnceAndRotate() throws Exception {
    String token = bd().token();
    JsonNode first = link(token);
    assertThat(first.path("code").asText()).matches("[2-9A-HJ-NP-Z]{8}");
    assertThat(first.path("url").asText())
        .isEqualTo("https://kol.example.com/register?code=" + first.path("code").asText());
    assertThat(first.path("status").asInt()).isEqualTo(1);
    assertThat(link(token).path("code").asText()).isEqualTo(first.path("code").asText());

    String body =
        mvc.perform(post("/admin-api/kol/register-code").header(ADMIN, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String rotated = json.readTree(body).at("/data/code").asText();
    assertThat(rotated).isNotEqualTo(first.path("code").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM crm_kol_register_code WHERE code = ?",
                Integer.class,
                first.path("code").asText()))
        .isZero();
    rejected(first.path("code").asText());
    mvc.perform(get("/kol-api/register/" + rotated))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bdUserName").isString());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE module = 'KOL' AND action IN"
                    + " ('Get register link', 'Rotate register link')",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void shouldRejectAccountsThatCannotBeABd() throws Exception {
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    mvc.perform(get("/admin-api/kol/register-code").header(ADMIN, admin))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.errorCode").value("KOL_INVALID_BD_USER"));

    String allScope = staff(admin, "OPERATOR", "ALL", List.of("kol:register-link", "kol:edit"));
    mvc.perform(get("/admin-api/kol/register-code").header(ADMIN, allScope))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.errorCode").value("KOL_INVALID_BD_USER"));

    String noEdit = staff(admin, "BD", "OWN", List.of("kol:register-link"));
    mvc.perform(post("/admin-api/kol/register-code").header(ADMIN, noEdit))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.errorCode").value("KOL_INVALID_BD_USER"));
  }

  @Test
  void shouldForbidAStaffAccountWithoutThePermission() throws Exception {
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    String token = staff(admin, "BD", "OWN", List.of("kol:view"));
    mvc.perform(get("/admin-api/kol/register-code").header(ADMIN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldRegisterIntoTheCompanyThatOwnsTheCode() throws Exception {
    Bd companyA = bd();
    var companyB = CompanySessions.openCompany(mvc, json, PASSWORD, "reg-b", name("badmin"));
    long bdB =
        CompanySessions.createStaff(
            mvc, json, companyB.token(), PASSWORD, name("bbd"), "BD", null, null);
    String tokenB =
        CompanySessions.login(
            mvc,
            json,
            jdbc.queryForObject("SELECT username FROM sys_user WHERE id = ?", String.class, bdB),
            PASSWORD);
    String codeA = link(companyA.token()).path("code").asText();
    String codeB = link(tokenB).path("code").asText();
    assertThat(codeB).isNotEqualTo(codeA);

    String email = name("ada") + "@example.com";
    register(codeA, email, sendCode(codeA, email), "ada_handle", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM crm_kol WHERE lower(email) = ?", Long.class, email))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM crm_kol_register_code WHERE code = ?", Long.class, codeB))
        .isEqualTo(companyB.tenantId());
  }

  @Test
  void shouldRejectTheLinkWhenTheBdOrTheCompanyStopsQualifying() throws Exception {
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    Bd owner = bd(admin);
    String code = link(owner.token()).path("code").asText();

    mvc.perform(
            put("/admin-api/system/users/" + owner.id() + "/status")
                .header(ADMIN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    rejected(code);
    mvc.perform(
            put("/admin-api/system/users/" + owner.id() + "/status")
                .header(ADMIN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":1}"))
        .andExpect(status().isOk());
    mvc.perform(get("/kol-api/register/" + code)).andExpect(status().isOk());

    mvc.perform(
            put("/admin-api/system/users/" + owner.id() + "/permissions")
                .header(ADMIN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"kol:register-link\"],\"dataScope\":\"OWN\"}"))
        .andExpect(status().isOk());
    rejected(code);

    mvc.perform(
            put("/admin-api/system/users/" + owner.id() + "/permissions")
                .header(ADMIN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"permissions\":[\"kol:edit\",\"kol:register-link\"],\"dataScope\":\"ALL\"}"))
        .andExpect(status().isOk());
    rejected(code);

    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "reg-off", name("off"));
    long otherBd =
        CompanySessions.createStaff(
            mvc, json, other.token(), PASSWORD, name("offbd"), "BD", null, null);
    String otherName =
        jdbc.queryForObject("SELECT username FROM sys_user WHERE id = ?", String.class, otherBd);
    String otherCode =
        link(CompanySessions.login(mvc, json, otherName, PASSWORD)).path("code").asText();
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    try {
      mvc.perform(
              put("/admin-api/platform/tenants/" + other.tenantId() + "/status")
                  .header(ADMIN, platform)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":0}"))
          .andExpect(status().isOk());
      rejected(otherCode);
    } finally {
      mvc.perform(
          put("/admin-api/platform/tenants/" + other.tenantId() + "/status")
              .header(ADMIN, platform)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"status\":1}"));
    }
    mvc.perform(get("/kol-api/register/not-a-code"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_CODE_INVALID"));
  }

  @Test
  void shouldEnforceTheEmailCodeRules() throws Exception {
    String code = link(bd().token()).path("code").asText();
    String email = "code-rules@example.com";
    String verification = sendCode(code, email);
    mvc.perform(
            post("/kol-api/register/email-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "email", email))))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_CODE_TOO_FREQUENT"));
    assertThat(mails("kol-register-code")).hasSize(1);

    redis.delete(cooldownKey(1L, email));
    String replacement = sendCode(code, email);
    assertThat(replacement).isNotEqualTo(verification);

    for (int attempt = 0; attempt < 5; attempt++) {
      mvc.perform(
              post("/kol-api/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(code, email, "000000", "handle", null)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_CODE_INVALID"));
    }
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, email, replacement, "handle", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_CODE_INVALID"));

    redis.delete(cooldownKey(1L, email));
    String once = sendCode(code, email);
    register(code, email, once, "handle", null);
    assertThat(redis.hasKey(codeKey(1L, email))).isFalse();
    // The address is already registered, and that check runs before the code is read.
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, email, once, "handle", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_EXISTS"));

    String fresh = "fresh-code@example.com";
    redis.delete(cooldownKey(1L, fresh));
    sendCode(code, fresh);
    redis.delete(codeKey(1L, fresh));
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, fresh, "123456", "fresh", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_CODE_INVALID"));
  }

  @Test
  void shouldCreateAnActivatedKolAndSignItIn() throws Exception {
    Bd owner = bd();
    String code = link(owner.token()).path("code").asText();
    String email = "joined@example.com";
    MvcResult result = register(code, email, sendCode(code, email), "creator_handle", "US");
    JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
    assertThat(data.path("tokenName").asText()).isEqualTo(KOL);
    String kolToken = data.path("token").asText();
    mvc.perform(get("/kol-api/me").header(KOL, kolToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.data.name").value("Ada"));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT register_source, account_status, status, credential_version,"
                + " (password_hash IS NOT NULL) AS hashed, bd_user_id, name, country,"
                + " social_accounts::text AS social FROM crm_kol WHERE lower(email) = ?",
            email);
    assertThat(number(row, "register_source")).isEqualTo(2);
    assertThat(number(row, "account_status")).isEqualTo(1);
    assertThat(number(row, "status")).isEqualTo(1);
    assertThat(number(row, "credential_version")).isZero();
    assertThat(row.get("hashed")).isEqualTo(true);
    assertThat(((Number) row.get("bd_user_id")).longValue()).isEqualTo(owner.id());
    assertThat(row.get("name")).isEqualTo("Ada");
    assertThat(row.get("country")).isEqualTo("US");
    assertThat(String.valueOf(row.get("social"))).contains("\"primary\": true").contains("TikTok");
    Long kolId =
        jdbc.queryForObject("SELECT id FROM crm_kol WHERE lower(email) = ?", Long.class, email);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_kol_bd_history WHERE kol_id = ? AND bd_user_id = ?"
                    + " AND end_time IS NULL",
                Integer.class,
                kolId,
                owner.id()))
        .isEqualTo(1);

    String params =
        jdbc.queryForObject(
            "SELECT request_params FROM sys_oper_log WHERE action = 'Self register'", String.class);
    assertThat(params).isEqualTo("{\"kolId\":\"" + kolId + "\"}");
    assertThat(
            jdbc.queryForObject(
                "SELECT username FROM sys_oper_log WHERE action = 'Self register'", String.class))
        .isEqualTo(email);
  }

  @Test
  void shouldRejectBlankName_whenRegistering() throws Exception {
    String code = link(bd().token()).path("code").asText();
    String email = "noname@example.com";
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, email, sendCode(code, email), "plain_handle", null, "   ")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldNotMailAnAddressThatAlreadyExists() throws Exception {
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    Bd owner = bd(admin);
    mvc.perform(
            post("/admin-api/kol/kols")
                .header(ADMIN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Existing\",\"email\":\"taken@example.com\",\"country\":\"US\","
                        + "\"bdUserId\":\""
                        + owner.id()
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountStatus").value(0))
        .andExpect(jsonPath("$.data.registerSource").value(1));
    String code = link(owner.token()).path("code").asText();
    int before = CapturedMails.SENT.size();
    mvc.perform(
            post("/kol-api/register/email-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("code", code, "email", "Taken@Example.com"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_EXISTS"));
    assertThat(CapturedMails.SENT).hasSize(before);
    assertThat(redis.hasKey(cooldownKey(1L, "taken@example.com"))).isFalse();
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, "taken@example.com", "123456", "taken", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_EXISTS"));

    mvc.perform(
            get("/admin-api/kol/kols").header(ADMIN, admin).param("keyword", "taken@example.com"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].accountStatus").value(0));
  }

  @Test
  void shouldShowAccountStatusOneAfterSelfRegistration() throws Exception {
    Bd owner = bd();
    String code = link(owner.token()).path("code").asText();
    String email = "listed@example.com";
    register(code, email, sendCode(code, email), "listed", null);
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    String list =
        mvc.perform(get("/admin-api/kol/kols").header(ADMIN, admin).param("keyword", email))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].accountStatus").value(1))
            .andExpect(jsonPath("$.data.items[0].registerSource").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(list).at("/data/items/0/id").asText();
    mvc.perform(get("/admin-api/kol/kols/" + id).header(ADMIN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountStatus").value(1))
        .andExpect(jsonPath("$.data.registerSource").value(2));
  }

  @Test
  void shouldLetOnlyOneConcurrentSubmitSucceed() throws Exception {
    String code = link(bd().token()).path("code").asText();
    String email = "race@example.com";
    String verification = sendCode(code, email);
    String payload = body(code, email, verification, "racer", null);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> call =
          () ->
              mvc.perform(
                      post("/kol-api/register")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(payload))
                  .andReturn()
                  .getResponse()
                  .getStatus();
      Future<Integer> first = pool.submit(call);
      Future<Integer> second = pool.submit(call);
      List<Integer> statuses =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
      assertThat(statuses).allMatch(status -> status == 200 || status == 400 || status == 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_kol WHERE lower(email) = ?", Integer.class, email))
        .isEqualTo(1);
  }

  @Test
  void shouldLimitByIpAndByEmail() throws Exception {
    String missing = "E2F3G4H5";
    for (int i = 0; i < 20; i++) {
      mvc.perform(
              post("/kol-api/register/email-codes")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          Map.of("code", missing, "email", "ip" + i + "@example.com"))))
          .andExpect(status().isNotFound());
    }
    mvc.perform(
            post("/kol-api/register/email-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("code", missing, "email", "over@example.com"))))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_TOO_FREQUENT"));

    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    for (int i = 0; i < 10; i++) {
      mvc.perform(
              post("/kol-api/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(missing, "same@example.com", "123456", "h" + i, null)))
          .andExpect(status().isNotFound());
    }
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(missing, "same@example.com", "123456", "hover", null)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_TOO_FREQUENT"));
  }

  private MvcResult register(
      String code, String email, String emailCode, String handle, String country) throws Exception {
    return register(code, email, emailCode, handle, country, "Ada");
  }

  private MvcResult register(
      String code, String email, String emailCode, String handle, String country, String name)
      throws Exception {
    return mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, email, emailCode, handle, country, name)))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String body(String code, String email, String emailCode, String handle, String country)
      throws Exception {
    return body(code, email, emailCode, handle, country, "Ada");
  }

  private String body(
      String code, String email, String emailCode, String handle, String country, String name)
      throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("code", code);
    payload.put("email", email);
    payload.put("emailCode", emailCode);
    payload.put("password", REGISTER_PASSWORD);
    payload.put("whatsapp", "+15551234567");
    payload.put("primaryPlatform", "TikTok");
    payload.put("primaryHandle", handle);
    if (name != null) {
      payload.put("name", name);
    }
    if (country != null) {
      payload.put("country", country);
    }
    return json.writeValueAsString(payload);
  }

  private String sendCode(String code, String email) throws Exception {
    int before = mails("kol-register-code").size();
    mvc.perform(
            post("/kol-api/register/email-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "email", email))))
        .andExpect(status().isOk());
    Matcher matcher = DIGITS.matcher(awaitMail("kol-register-code", before + 1).text());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private void rejected(String code) throws Exception {
    mvc.perform(get("/kol-api/register/" + code))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_CODE_INVALID"));
    mvc.perform(
            post("/kol-api/register/email-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("code", code, "email", name("x") + "@example.com"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_CODE_INVALID"));
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, name("y") + "@example.com", "123456", "handle", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("KOL_REGISTER_CODE_INVALID"));
  }

  private JsonNode link(String token) throws Exception {
    String body =
        mvc.perform(get("/admin-api/kol/register-code").header(ADMIN, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).path("data");
  }

  private Bd bd() throws Exception {
    return bd(CompanySessions.companyAdmin(mvc, json, PASSWORD));
  }

  private Bd bd(String admin) throws Exception {
    String username = name("bd");
    long id = CompanySessions.createStaff(mvc, json, admin, PASSWORD, username, "BD", null, null);
    return new Bd(id, CompanySessions.login(mvc, json, username, PASSWORD));
  }

  private String staff(String admin, String template, String scope, List<String> permissions)
      throws Exception {
    String username = name("st");
    CompanySessions.createStaff(mvc, json, admin, PASSWORD, username, template, scope, permissions);
    return CompanySessions.login(mvc, json, username, PASSWORD);
  }

  private static int number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).intValue();
  }

  private static String name(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String codeKey(long tenantId, String email) {
    return "kol:register:email-code:" + tenantId + ":" + SecureTokens.hash(email);
  }

  private static String cooldownKey(long tenantId, String email) {
    return "kol:register:email-cooldown:" + tenantId + ":" + SecureTokens.hash(email);
  }

  private List<MailMessage> mails(String template) {
    return CapturedMails.SENT.stream().filter(mail -> mail.template().equals(template)).toList();
  }

  private MailMessage awaitMail(String template, int atLeast) {
    MailMessage[] holder = new MailMessage[1];
    TestWait.until(
        java.time.Duration.ofSeconds(10),
        java.time.Duration.ofMillis(100),
        () -> {
          List<MailMessage> found = new ArrayList<>(mails(template));
          if (found.size() >= atLeast) {
            holder[0] = found.get(found.size() - 1);
            return true;
          }
          return false;
        },
        "no " + template + " mail arrived (have " + CapturedMails.SENT + ")");
    return holder[0];
  }

  private record Bd(long id, String token) {}
}
