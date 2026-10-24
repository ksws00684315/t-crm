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
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.service.KolInvites;
import com.tcrm.admin.kol.service.KolWriter;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import com.tcrm.admin.shared.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 12a against real PostgreSQL and Redis: KOL account migration, login across companies,
 * lockout isolation, token namespaces, invitation lifecycle, forgot/reset and password change.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolAuthIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_TOKEN = "X-Admin-Token";
  private static final String KOL_TOKEN = "X-Kol-Token";
  private static final Pattern ACTIVATE_LINK =
      Pattern.compile("activate\\?token=([A-Za-z0-9_-]{43})");
  private static final Pattern RESET_LINK = Pattern.compile("reset\\?token=([A-Za-z0-9_-]{43})");

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

  /** Captures every outgoing mail so invitations and reset links can be exercised end to end. */
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
  private final KolWriter writer;
  private final KolAccountStore accounts;
  private final KolInvites invites;
  private final BCryptPasswordEncoder passwords;

  @Autowired
  KolAuthIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      KolWriter writer,
      KolAccountStore accounts,
      KolInvites invites,
      BCryptPasswordEncoder passwords) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.writer = writer;
    this.accounts = accounts;
    this.invites = invites;
    this.passwords = passwords;
  }

  @BeforeEach
  void prepareIndependentData() {
    jdbc.update("DELETE FROM crm_kol_auth_token");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    CapturedMails.SENT.clear();
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void shouldCreateAccountColumnsAndTokenTable_whenMigrationsRun() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'crm_kol'"
                    + " AND column_name IN ('password_hash','account_status','credential_version',"
                    + "'last_login_time')",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name ="
                    + " 'crm_kol_auth_token'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version ="
                    + " '202610171400'",
                Integer.class))
        .isEqualTo(1);
    // ck_crm_kol_account_password: activating without a hash is rejected, and so is clearing the
    // hash of an activated account; the store writes both columns in one statement.
    long kolId = activatedKolInviteOnly(1L, "constraint@example.com");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.update("UPDATE crm_kol SET account_status = 1 WHERE id = ?", kolId))
        .hasMessageContaining("ck_crm_kol_account_password");
    accounts.activate(kolId, passwords.encode("some-password-1"));
    assertThat(
            jdbc.queryForObject(
                "SELECT account_status FROM crm_kol WHERE id = ?", Integer.class, kolId))
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> jdbc.update("UPDATE crm_kol SET password_hash = NULL WHERE id = ?", kolId))
        .hasMessageContaining("ck_crm_kol_account_password");
  }

  @Test
  void shouldLoginSingleCompany_withRightPasswordOnly() throws Exception {
    long kolId = activatedKol(1L, "solo@example.com", "first-password");
    var login = kolLogin("solo@example.com", "first-password");
    assertThat(login.at("/data/tokenName").asString()).isEqualTo("X-Kol-Token");
    assertThat(login.at("/data/token").asString()).isNotEmpty();
    assertThat(login.at("/data/expiresIn").asLong()).isEqualTo(604800L);
    assertThat(login.at("/data/kol/email").asString()).isEqualTo("solo@example.com");
    assertThat(login.at("/data/kol/tenantName").asString()).isEqualTo("默认公司");
    String token = login.at("/data/token").asString();
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("solo@example.com"));
    mvc.perform(post("/kol-api/auth/logout").header(KOL_TOKEN, token)).andExpect(status().isOk());
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, token)).andExpect(status().isUnauthorized());
    // Wrong password answers the same 401 as an unknown email (§3.4).
    mvc.perform(kolLoginRequest("solo@example.com", "wrong-password"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
    mvc.perform(kolLoginRequest("nobody@example.com", "wrong-password"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
    assertThat(
            jdbc.queryForObject(
                "SELECT credential_version FROM crm_kol WHERE id = ?", Integer.class, kolId))
        .isEqualTo(1);
  }

  @Test
  void shouldReturnCompanySelection_whenSameEmailHasTwoUsableAccounts() throws Exception {
    var second = CompanySessions.openCompany(mvc, json, PASSWORD, "beta", "beta-admin");
    activatedKol(1L, "dual@example.com", "shared-password");
    activatedKol(second.tenantId(), "dual@example.com", "shared-password");

    var body = kolLogin("dual@example.com", "shared-password");
    assertThat(body.at("/data/selectionToken").asString()).isNotEmpty();
    assertThat(body.at("/data/token").isMissingNode()).isTrue();
    assertThat(body.at("/data/companies").size()).isEqualTo(2);
    assertThat(body.at("/data/companies/0/tenantId").asString()).isEqualTo("1");
    assertThat(body.at("/data/companies/1/tenantId").asString())
        .isEqualTo(String.valueOf(second.tenantId()));
    String selectionToken = body.at("/data/selectionToken").asString();
    assertThat(redis.getExpire("kol:auth:selection:" + selectionToken, TimeUnit.SECONDS))
        .isBetween(240L, 300L);

    // Valid exchange first (the credential is consumed by any select, even a rejected one).
    var login =
        json.readTree(
            mvc.perform(
                    json(
                        post("/kol-api/auth/login/select"),
                        "{\"selectionToken\":\""
                            + selectionToken
                            + "\",\"tenantId\":\""
                            + second.tenantId()
                            + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kol.tenantName").value("beta-admin"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    // Single use: the second exchange is rejected.
    mvc.perform(
            json(
                post("/kol-api/auth/login/select"),
                "{\"selectionToken\":\""
                    + selectionToken
                    + "\",\"tenantId\":\""
                    + second.tenantId()
                    + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_LOGIN_SELECTION_INVALID"));
    // A fresh credential only opens companies that passed the password check.
    String fresh =
        kolLogin("dual@example.com", "shared-password").at("/data/selectionToken").asString();
    mvc.perform(
            json(
                post("/kol-api/auth/login/select"),
                "{\"selectionToken\":\"" + fresh + "\",\"tenantId\":\"999\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_LOGIN_SELECTION_INVALID"));
    // The session is bound to the chosen company: /me serves that company's KOL.
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, login.at("/data/token").asString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantName").value("beta-admin"));
  }

  @Test
  void shouldLockKolLogin_afterFivePairFailures_withoutTouchingAdminCounters() throws Exception {
    activatedKol(1L, "lock@example.com", "right-password");
    for (int i = 0; i < 4; i++) {
      mvc.perform(kolLoginRequest("lock@example.com", "wrong-password"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(kolLoginRequest("lock@example.com", "wrong-password"))
        .andExpect(status().is(429))
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_LOCKED"));
    // Locked even with the correct password.
    mvc.perform(kolLoginRequest("lock@example.com", "right-password")).andExpect(status().is(429));
    // The administrator namespace counts on different keys: login still works.
    assertThat(CompanySessions.login(mvc, json, "bootstrap", PASSWORD)).isNotEmpty();
  }

  @Test
  void shouldDisableLoginsAndSessions_whenKolOrCompanyIsDisabled() throws Exception {
    long kolId = activatedKol(1L, "off@example.com", "some-password-1");
    String token = kolLogin("off@example.com", "some-password-1").at("/data/token").asString();
    jdbc.update("UPDATE crm_kol SET status = 0 WHERE id = ?", kolId);
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));
    mvc.perform(kolLoginRequest("off@example.com", "some-password-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));

    jdbc.update("UPDATE crm_kol SET status = 1 WHERE id = ?", kolId);
    var second = CompanySessions.openCompany(mvc, json, PASSWORD, "offline", "offline-admin");
    long kol2 = activatedKol(second.tenantId(), "off2@example.com", "some-password-1");
    String token2 = kolLogin("off2@example.com", "some-password-1").at("/data/token").asString();
    jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", second.tenantId());
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, token2))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));
    mvc.perform(kolLoginRequest("off2@example.com", "some-password-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_DISABLED"));
  }

  @Test
  void shouldKeepTokenNamespacesSeparate_forAdminAndKol() throws Exception {
    activatedKol(1L, "island@example.com", "some-password-1");
    String kolToken =
        kolLogin("island@example.com", "some-password-1").at("/data/token").asString();
    String adminToken = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    // A KOL token is no administrator session...
    mvc.perform(get("/admin-api/auth/me").header(ADMIN_TOKEN, kolToken))
        .andExpect(status().isUnauthorized());
    // ...and an administrator token is no KOL session, whatever header carries it.
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, adminToken))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/kol-api/me").header(ADMIN_TOKEN, kolToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldInviteAutomatically_andControlTheTokenLifecycle() throws Exception {
    String admin = CompanySessions.companyAdmin(mvc, json, PASSWORD, 1L, "inviter");
    long kolId =
        Long.parseLong(
            json.readTree(
                    mvc.perform(
                            json(
                                    post("/admin-api/kol/kols"),
                                    "{\"name\":\"Invitee\",\"email\":\"invite@example.com\","
                                        + "\"country\":\"US\"}")
                                .header(ADMIN_TOKEN, admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .at("/data/id")
                .asText());
    MailMessage mail = awaitMail("kol-invite");
    assertThat(mail.to()).isEqualTo("invite@example.com");
    assertThat(mail.subject()).isEqualTo("[默认公司] Set up your creator account / 设置您的达人账号");
    Matcher matcher = ACTIVATE_LINK.matcher(mail.text());
    assertThat(matcher.find()).isTrue();
    String firstToken = matcher.group(1);

    // Only the digest is stored, never the raw token.
    String stored =
        jdbc.queryForObject(
            "SELECT token_hash FROM crm_kol_auth_token WHERE kol_id = ?", String.class, kolId);
    assertThat(stored).hasSize(64).isNotEqualTo(firstToken);

    // Resending invalidates the previous link: the old token answers 400, the new one activates.
    mvc.perform(post("/admin-api/kol/kols/" + kolId + "/invite").header(ADMIN_TOKEN, admin))
        .andExpect(status().isOk());
    Matcher second = ACTIVATE_LINK.matcher(awaitMail("kol-invite", 2).text());
    assertThat(second.find()).isTrue();
    String secondToken = second.group(1);
    assertThat(secondToken).isNotEqualTo(firstToken);
    mvc.perform(
            json(
                post("/kol-api/auth/activate"),
                "{\"token\":\"" + firstToken + "\",\"password\":\"chosen-password\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_AUTH_TOKEN_INVALID"));
    mvc.perform(
            json(
                post("/kol-api/auth/activate"),
                "{\"token\":\"" + secondToken + "\",\"password\":\"chosen-password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tokenName").value("X-Kol-Token"))
        .andExpect(jsonPath("$.data.kol.email").value("invite@example.com"));
    assertThat(
            jdbc.queryForObject(
                "SELECT account_status FROM crm_kol WHERE id = ?", Integer.class, kolId))
        .isEqualTo(1);
    // An invitation link works only once; a resent invitation for an activated KOL is a 409.
    mvc.perform(
            json(
                post("/kol-api/auth/activate"),
                "{\"token\":\"" + secondToken + "\",\"password\":\"chosen-password\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_AUTH_TOKEN_INVALID"));
    mvc.perform(post("/admin-api/kol/kols/" + kolId + "/invite").header(ADMIN_TOKEN, admin))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_ALREADY_ACTIVATED"));
  }

  @Test
  void shouldRejectInviteTokens_after72Hours() throws Exception {
    long kolId = activatedKolInviteOnly(1L, "expired@example.com");
    Matcher invite = ACTIVATE_LINK.matcher(awaitMail("kol-invite").text());
    assertThat(invite.find()).isTrue();
    String token = invite.group(1);
    jdbc.update(
        "UPDATE crm_kol_auth_token SET expires_at = now() - interval '1 second'"
            + " WHERE kol_id = ? AND purpose = 'INVITE'",
        kolId);
    mvc.perform(
            json(
                post("/kol-api/auth/activate"),
                "{\"token\":\"" + token + "\",\"password\":\"chosen-password\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_AUTH_TOKEN_INVALID"));
  }

  @Test
  void shouldForgotAndReset_withOneMailPerEmail_andRateLimits() throws Exception {
    var second = CompanySessions.openCompany(mvc, json, PASSWORD, "gamma", "gamma-admin");
    activatedKol(1L, "forgot@example.com", "old-password-1");
    activatedKol(second.tenantId(), "forgot@example.com", "old-password-1");

    // Unknown emails answer success without sending anything.
    mvc.perform(json(post("/kol-api/auth/forgot"), "{\"email\":\"ghost@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist());
    assertThat(CapturedMails.SENT).isEmpty();

    // One mail, one link per company; each link only resets that company's account.
    mvc.perform(json(post("/kol-api/auth/forgot"), "{\"email\":\"forgot@example.com\"}"))
        .andExpect(status().isOk());
    MailMessage mail = awaitMail("kol-reset");
    // Every company link appears twice (English and Chinese sections): two distinct links.
    assertThat(RESET_LINK.matcher(mail.text()).results().map(r -> r.group(1)).distinct().count())
        .isEqualTo(2);
    Matcher links = RESET_LINK.matcher(mail.text());
    assertThat(links.find()).isTrue();
    String firstLink = links.group(1);
    assertThat(links.find()).isTrue();
    String secondLink = links.group(1);

    mvc.perform(
            json(
                post("/kol-api/auth/reset"),
                "{\"token\":\"" + firstLink + "\",\"password\":\"new-password-1\"}"))
        .andExpect(status().isOk());
    // The used link is dead, the other company's link still works.
    mvc.perform(
            json(
                post("/kol-api/auth/reset"),
                "{\"token\":\"" + firstLink + "\",\"password\":\"other-password\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_AUTH_TOKEN_INVALID"));
    mvc.perform(
            json(
                post("/kol-api/auth/reset"),
                "{\"token\":\"" + secondLink + "\",\"password\":\"second-password\"}"))
        .andExpect(status().isOk());
    // Each company's account got its own new password.
    kolLogin("forgot@example.com", "new-password-1");
    kolLogin("forgot@example.com", "second-password");

    // Per-email budget: at most three mails per ten minutes, silently. One mail was already sent
    // above, so two more go out and the rest is suppressed without an error.
    CapturedMails.SENT.clear();
    for (int i = 0; i < 4; i++) {
      mvc.perform(json(post("/kol-api/auth/forgot"), "{\"email\":\"forgot@example.com\"}"))
          .andExpect(status().isOk());
    }
    awaitMail("kol-reset", 2);
    // Must wait past the silent per-email mail budget window; cannot poll a condition.
    Thread.sleep(500);
    assertThat(CapturedMails.SENT).hasSize(2);
  }

  @Test
  void shouldChangePassword_andKeepOnlyTheNewTokenAlive() throws Exception {
    activatedKol(1L, "change@example.com", "old-password-1");
    JsonNode first = kolLogin("change@example.com", "old-password-1");
    String oldToken = first.at("/data/token").asString();
    mvc.perform(
            json(
                put("/kol-api/me/password").header(KOL_TOKEN, oldToken),
                "{\"oldPassword\":\"wrong-password\",\"newPassword\":\"next-password-1\"}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.errorCode").value("AUTH_OLD_PASSWORD_INCORRECT"));
    JsonNode second =
        json.readTree(
            mvc.perform(
                    json(
                        put("/kol-api/me/password").header(KOL_TOKEN, oldToken),
                        "{\"oldPassword\":\"old-password-1\",\"newPassword\":\"next-password-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenName").value("X-Kol-Token"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    String newToken = second.at("/data/token").asString();
    assertThat(newToken).isNotEqualTo(oldToken);
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, oldToken))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, newToken)).andExpect(status().isOk());
    // The fresh password signs in again; the old one no longer does.
    kolLogin("change@example.com", "next-password-1");
    mvc.perform(kolLoginRequest("change@example.com", "old-password-1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldAuditKolAuthOperations_withoutAnySecrets() throws Exception {
    long kolId = activatedKolInviteOnly(1L, "audit@example.com");
    Matcher invite = ACTIVATE_LINK.matcher(awaitMail("kol-invite").text());
    assertThat(invite.find()).isTrue();
    String raw = invite.group(1);
    mvc.perform(
            json(
                post("/kol-api/auth/activate"),
                "{\"token\":\"" + raw + "\",\"password\":\"chosen-password\"}"))
        .andExpect(status().isOk());
    String token = kolLogin("audit@example.com", "chosen-password").at("/data/token").asString();
    mvc.perform(
            json(
                put("/kol-api/me/password").header(KOL_TOKEN, token),
                "{\"oldPassword\":\"chosen-password\",\"newPassword\":\"renewed-pass\"}"))
        .andExpect(status().isOk());
    mvc.perform(json(post("/kol-api/auth/forgot"), "{\"email\":\"audit@example.com\"}"))
        .andExpect(status().isOk());
    Matcher resetLink = RESET_LINK.matcher(awaitMail("kol-reset").text());
    assertThat(resetLink.find()).isTrue();
    String reset = resetLink.group(1);
    mvc.perform(
            json(
                post("/kol-api/auth/reset"),
                "{\"token\":\"" + reset + "\",\"password\":\"final-password\"}"))
        .andExpect(status().isOk());

    List<String> actions =
        jdbc.queryForList(
            "SELECT action || '|' || request_params FROM sys_oper_log WHERE module = 'KolAuth'"
                + " ORDER BY oper_time, id",
            String.class);
    assertThat(actions)
        .extracting(a -> a.substring(0, a.indexOf('|')))
        .containsExactly("Activate", "Change password", "Reset password");
    for (String row : actions) {
      assertThat(row)
          .doesNotContain("chosen-password")
          .doesNotContain("renewed-pass")
          .doesNotContain("final-password")
          .doesNotContain(raw)
          .doesNotContain(reset)
          .doesNotContain("kol.example.com/activate")
          .doesNotContain("kol.example.com/reset");
    }
  }

  @Test
  void shouldUpdateOwnProfile_withWhitelistedFieldsAndAudit() throws Exception {
    long kolId = activatedKol(1L, "profile@example.com", "some-password-1");
    String code =
        jdbc.queryForObject("SELECT kol_code FROM crm_kol WHERE id = ?", String.class, kolId);
    jdbc.update(
        "UPDATE crm_kol SET social_accounts = ? WHERE id = ?",
        "[{\"platform\":\"TikTok\",\"handle\":\"@seeded\",\"url\":null,\"followers\":null,"
            + "\"primary\":true},{\"platform\":\"Instagram\",\"handle\":\"@second\","
            + "\"url\":null,\"followers\":10,\"primary\":false}]",
        kolId);
    String token = kolLogin("profile@example.com", "some-password-1").at("/data/token").asString();

    mvc.perform(get("/kol-api/me").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.systemId").value(code))
        .andExpect(jsonPath("$.data.primaryPlatform").value("TikTok"))
        .andExpect(jsonPath("$.data.primaryHandle").value("@seeded"))
        .andExpect(jsonPath("$.data.loginStatus").value("NORMAL"))
        .andExpect(jsonPath("$.data.commissionRate").doesNotExist());

    // The whitelist ignores read-only fields sent along; the pairing rule guards the social slot.
    mvc.perform(
            json(
                put("/kol-api/me").header(KOL_TOKEN, token),
                "{\"name\":\"Renamed Creator\",\"email\":\"hacker@example.com\","
                    + "\"primaryPlatform\":\"YouTube\",\"primaryHandle\":\"@new\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Renamed Creator"))
        .andExpect(jsonPath("$.data.primaryPlatform").value("YouTube"))
        .andExpect(jsonPath("$.data.email").value("profile@example.com"));

    assertThat(jdbc.queryForObject("SELECT name FROM crm_kol WHERE id = ?", String.class, kolId))
        .isEqualTo("Renamed Creator");
    assertThat(jdbc.queryForObject("SELECT email FROM crm_kol WHERE id = ?", String.class, kolId))
        .isEqualTo("profile@example.com");
    String socials =
        jdbc.queryForObject(
            "SELECT social_accounts FROM crm_kol WHERE id = ?", String.class, kolId);
    assertThat(socials).contains("\"YouTube\"").contains("@new").contains("@second");
    assertThat(socials).doesNotContain("@seeded");
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM sys_oper_log WHERE module = 'KolProfile'", String.class))
        .containsExactly("Update profile");

    mvc.perform(
            json(
                put("/kol-api/me").header(KOL_TOKEN, token),
                "{\"name\":\"X Creator\",\"primaryHandle\":\"@only\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  private static KolDO baseKol(String email) {
    KolDO kol = new KolDO();
    kol.setName("Kol");
    kol.setEmail(email);
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setStatus(1);
    return kol;
  }

  private long activatedKol(long tenantId, String email, String password) {
    KolDO kol = baseKol(email);
    TenantScope.runCompany(tenantId, () -> writer.insert(kol, null, 1L));
    accounts.activate(kol.getId(), passwords.encode(password));
    return kol.getId();
  }

  /** Back-office style KOL without a password; the invitation mail is awaited by the caller. */
  private long activatedKolInviteOnly(long tenantId, String email) {
    KolDO kol = baseKol(email);
    TenantScope.runCompany(tenantId, () -> writer.insert(kol, null, 1L));
    invites.issueAndSend(kol.getId());
    return kol.getId();
  }

  private MockHttpServletRequestBuilder kolLoginRequest(String email, String password) {
    return json(
        post("/kol-api/auth/login"),
        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private JsonNode kolLogin(String email, String password) throws Exception {
    return json.readTree(
        mvc.perform(kolLoginRequest(email, password))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MailMessage awaitMail(String template) throws InterruptedException {
    return awaitMail(template, 1);
  }

  private MailMessage awaitMail(String template, int atLeast) {
    MailMessage[] holder = new MailMessage[1];
    TestWait.until(
        java.time.Duration.ofSeconds(10),
        java.time.Duration.ofMillis(100),
        () -> {
          List<MailMessage> matching =
              CapturedMails.SENT.stream().filter(m -> m.template().equals(template)).toList();
          if (matching.size() >= atLeast) {
            holder[0] = matching.getLast();
            return true;
          }
          return false;
        },
        "no " + template + " mail arrived (have " + CapturedMails.SENT + ")");
    return holder[0];
  }
}
