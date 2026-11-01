package com.tcrm.admin.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.service.KolWriter;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 15a against real PostgreSQL and Redis: payout account lifecycle from the KOL portal, admin
 * list and detail (no review since task 82), BD data scope, company isolation, file rules, audit.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PayoutAccountIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String ADMIN_TOKEN = "X-Admin-Token";
  private static final String KOL_TOKEN = "X-Kol-Token";
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
  };

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
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final KolWriter writer;
  private final KolAccountStore accounts;
  private final BCryptPasswordEncoder passwords;

  @Autowired
  PayoutAccountIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      KolWriter writer,
      KolAccountStore accounts,
      BCryptPasswordEncoder passwords) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.writer = writer;
    this.accounts = accounts;
    this.passwords = passwords;
  }

  private String admin;

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_payout_account");
    jdbc.update("DELETE FROM crm_file");
    for (String table :
        List.of(
            "crm_kol_bd_history",
            "crm_kol",
            "sys_user_permission",
            "sys_user_tenant",
            "sys_user",
            "crm_task_file",
            "crm_task",
            "sys_oper_log")) {
      jdbc.update(
          "DELETE FROM "
              + table
              + (table.equals("sys_user") ? " WHERE username <> 'bootstrap'" : ""));
    }
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  // ------------------------------------------------------------------ helpers

  private long staff(String username, String scope, String... permissions) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/system/users")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username",
                                username,
                                "password",
                                "staff-pass-123",
                                "nickname",
                                username,
                                "role",
                                "STAFF",
                                "postTemplate",
                                "BD",
                                "dataScope",
                                scope,
                                "permissions",
                                List.of(permissions)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private String loginStaff(String username) throws Exception {
    return CompanySessions.login(mvc, json, username, "staff-pass-123");
  }

  /** KOL created through the admin API, assigned to {@code bdUserId}, activated and logged in. */
  private record LoggedKol(long id, String kolCode, String token) {}

  private LoggedKol kol(String name, String email, Long bdUserId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(ADMIN_TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"email\":\""
                            + email
                            + "\",\"country\":\"US\",\"socialAccounts\":[]"
                            + (bdUserId == null ? "" : ",\"bdUserId\":\"" + bdUserId + "\"")
                            + "}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = Long.parseLong(json.readTree(body).at("/data/id").asText());
    String kolCode = json.readTree(body).at("/data/kolCode").asText();
    return activated(new LoggedKol(id, kolCode, null), email);
  }

  private LoggedKol activated(LoggedKol kol, String email) throws Exception {
    long tenantId = tenantOf(kol.id());
    TenantScope.runCompany(
        tenantId, () -> accounts.activate(kol.id(), passwords.encode("kol-pass-123")));
    String login =
        mvc.perform(
                post("/kol-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"kol-pass-123\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(login).at("/data/token").asText();
    if (token.isEmpty()) {
      // The same email exists in several companies: pick this KOL's company explicitly.
      String selection = json.readTree(login).at("/data/selectionToken").asText();
      token =
          mvc.perform(
                  post("/kol-api/auth/login/select")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"selectionToken\":\""
                              + selection
                              + "\",\"tenantId\":\""
                              + tenantId
                              + "\"}"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString()
              .transform(body -> json.readTree(body).at("/data/token").asText());
    }
    return new LoggedKol(kol.id(), kol.kolCode(), token);
  }

  private long tenantOf(long kolId) {
    return jdbc.queryForObject("SELECT tenant_id FROM crm_kol WHERE id = ?", Long.class, kolId);
  }

  /** The audit writer is asynchronous; poll until the row for the action shows up. */
  private long awaitAuditRows(String action) {
    long deadline = System.currentTimeMillis() + 5_000;
    long count = 0L;
    while (System.currentTimeMillis() <= deadline) {
      Long found =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_oper_log WHERE module = 'PayoutAccount' AND action = ?",
              Long.class,
              action);
      count = found == null ? 0L : found;
      if (count > 0) {
        return count;
      }
      try {
        // Poll interval while waiting for the async audit row.
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for audit row " + action, e);
      }
    }
    return count;
  }

  private long createPaypal(LoggedKol kol, String account) throws Exception {
    String body =
        mvc.perform(
                post("/kol-api/payout-accounts")
                    .header(KOL_TOKEN, kol.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"accountType\":\"PAYPAL\",\"account\":\""
                            + account
                            + "\",\"holderName\":\"Alice D\",\"currency\":\"USD\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/id").asText());
  }

  private long uploadQr(LoggedKol kol, String fileName, byte[] content) throws Exception {
    String body =
        mvc.perform(
                multipart("/kol-api/files")
                    .file(new MockMultipartFile("file", fileName, "image/png", content))
                    .header(KOL_TOKEN, kol.token()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(body).at("/data/fileId").asText());
  }

  // ------------------------------------------------------------------- tests

  @Test
  void shouldRunTheWholeLifecycle_fromCreationToDisableAndDefault() throws Exception {
    long bd = staff("bd-review", "OWN", "payout-account:review", "kol:edit");
    String reviewer = loginStaff("bd-review");
    LoggedKol kol = kol("Alice", "alice@example.com", bd);
    long qrFile = uploadQr(kol, "qr.png", PNG);

    // New USDT account with the uploaded QR; the file is backfilled in the same transaction.
    MvcResult usdt =
        mvc.perform(
                post("/kol-api/payout-accounts")
                    .header(KOL_TOKEN, kol.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"accountType\":\"USDT\",\"account\":\"TQ5xnPW9tZ3kA7qL9aZ\","
                            + "\"network\":\"TRC20\",\"qrFileId\":\""
                            + qrFile
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    long usdtId =
        Long.parseLong(
            json.readTree(usdt.getResponse().getContentAsString()).at("/data/id").asText());
    long paypalId = createPaypal(kol, "alice.pay@example.com");
    assertThat(jdbc.queryForObject("SELECT biz_id FROM crm_file WHERE id = ?", Long.class, qrFile))
        .isEqualTo(usdtId);

    // No review since task 82: new accounts are APPROVED at once and carry no reviewer.
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM crm_payout_account WHERE kol_id = ?", String.class, kol.id()))
        .containsOnly("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_payout_account"
                    + " WHERE kol_id = ?"
                    + " AND (reviewed_by IS NOT NULL OR reviewed_time IS NOT NULL)",
                Long.class,
                kol.id()))
        .isZero();

    // The owner sees full accounts; the database only ever holds ciphertext.
    String mine =
        mvc.perform(get("/kol-api/payout-accounts").header(KOL_TOKEN, kol.token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(mine).contains("alice.pay@example.com").contains("TQ5xnPW9tZ3kA7qL9aZ");
    assertThat(jdbc.queryForList("SELECT account_cipher FROM crm_payout_account", String.class))
        .allSatisfy(cipher -> assertThat(cipher).startsWith("v1:"));

    // Reviewer queue: masked values only, every view of the detail audited.
    String queue =
        mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, reviewer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(queue).contains("al***@example.com").contains("TQ5x…L9aZ");
    assertThat(queue).doesNotContain("alice.pay@example.com").doesNotContain("TQ5xnPW9tZ3kA7qL9aZ");
    mvc.perform(
            get("/admin-api/commission/payout-accounts/" + paypalId).header(ADMIN_TOKEN, reviewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.account").value("alice.pay@example.com"));
    assertThat(awaitAuditRows("View detail")).isEqualTo(1);

    // The new PayPal account is already APPROVED, so it can be the default right away.
    mvc.perform(
            put("/kol-api/payout-accounts/" + paypalId + "/default").header(KOL_TOKEN, kol.token()))
        .andExpect(status().isOk());

    // Editing an approved account is allowed: it stays APPROVED and the masked value follows.
    mvc.perform(
            put("/kol-api/payout-accounts/" + paypalId)
                .header(KOL_TOKEN, kol.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"PAYPAL\",\"account\":\"resubmitted@example.com\","
                        + "\"holderName\":\"Alice D\",\"currency\":\"USD\"}"))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM crm_payout_account WHERE id = ?", String.class, paypalId))
        .isEqualTo("APPROVED");
    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolId=" + kol.id())
                .header(ADMIN_TOKEN, reviewer))
        .andExpect(
            jsonPath("$.data.items[?(@.id == '" + paypalId + "')].accountMasked")
                .value("re***@example.com"))
        .andExpect(
            jsonPath("$.data.items[?(@.id == '" + paypalId + "')].status").value("APPROVED"));

    // Switching the default to the USDT account: exactly one remains.
    mvc.perform(
            put("/kol-api/payout-accounts/" + usdtId + "/default").header(KOL_TOKEN, kol.token()))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM crm_payout_account WHERE kol_id = ? AND is_default",
                Long.class,
                kol.id()))
        .containsExactly(usdtId);

    // Disabling the default clears the flag and hides the account from its owner for good.
    mvc.perform(
            put("/kol-api/payout-accounts/" + usdtId + "/disable").header(KOL_TOKEN, kol.token()))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT is_default FROM crm_payout_account WHERE id = ?", Boolean.class, usdtId))
        .isFalse();
    mvc.perform(get("/kol-api/payout-accounts").header(KOL_TOKEN, kol.token()))
        .andExpect(jsonPath("$.data[?(@.id == '" + usdtId + "')]").doesNotExist());
    mvc.perform(
            put("/kol-api/payout-accounts/" + usdtId)
                .header(KOL_TOKEN, kol.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"PAYPAL\",\"account\":\"x@example.com\",\"holderName\":\"A\",\"currency\":\"USD\"}"))
        .andExpect(status().isNotFound());

    // The QR of the disabled account stays downloadable for the reviewer.
    mvc.perform(get("/admin-api/files/" + qrFile).header(ADMIN_TOKEN, reviewer))
        .andExpect(status().isOk());

    // KOL writes are audited with the masked account only.
    awaitAuditRows("Disable");
    List<String> kolAudit =
        jdbc.queryForList(
            "SELECT action || '|' || request_params FROM sys_oper_log"
                + " WHERE module = 'PayoutAccount' AND username = 'alice@example.com'"
                + " ORDER BY oper_time, id",
            String.class);
    assertThat(kolAudit.stream().map(a -> a.substring(0, a.indexOf('|'))))
        .contains("Create", "Edit", "Set default", "Disable");
    for (String row : kolAudit) {
      assertThat(row).doesNotContain("alice.pay@example.com").doesNotContain("TQ5xnPW9tZ3kA7qL9aZ");
    }
  }

  @Test
  void shouldEnforceLimitsAndFileRules() throws Exception {
    LoggedKol kol = kol("Bob", "bob@example.com", null);
    // A dedicated KOL for the cap, so Bob stays free for the file-rule checks below.
    LoggedKol maxed = kol("Max", "max@example.com", null);
    for (int i = 0; i < 5; i++) {
      createPaypal(maxed, "max" + i + "@example.com");
    }
    mvc.perform(
            post("/kol-api/payout-accounts")
                .header(KOL_TOKEN, maxed.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"PAYPAL\",\"account\":\"max6@example.com\","
                        + "\"holderName\":\"Max\",\"currency\":\"USD\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("PAYOUT_ACCOUNT_LIMIT"));

    // Type sniffing ignores the file name and the declared content type.
    mvc.perform(
            multipart("/kol-api/files")
                .file(
                    new MockMultipartFile(
                        "file", "fake.png", "image/png", "not a png at all".getBytes()))
                .header(KOL_TOKEN, kol.token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("FILE_INVALID"));
    byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
    System.arraycopy(PNG, 0, tooLarge, 0, PNG.length);
    mvc.perform(
            multipart("/kol-api/files")
                .file(new MockMultipartFile("file", "big.png", "image/png", tooLarge))
                .header(KOL_TOKEN, kol.token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));

    // A file belongs to its uploader and to at most one account.
    LoggedKol other = kol("Cara", "cara@example.com", null);
    long ownFile = uploadQr(other, "mine.png", PNG);
    mvc.perform(get("/kol-api/files/" + ownFile).header(KOL_TOKEN, kol.token()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/kol-api/payout-accounts")
                .header(KOL_TOKEN, kol.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"USDT\",\"account\":\"OtherWallet123\","
                        + "\"network\":\"ERC20\",\"qrFileId\":\""
                        + ownFile
                        + "\"}"))
        .andExpect(status().isNotFound());
    long used = uploadQr(other, "used.png", PNG);
    long account =
        Long.parseLong(
            mvc.perform(
                    post("/kol-api/payout-accounts")
                        .header(KOL_TOKEN, other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"accountType\":\"USDT\",\"account\":\"BobWallet123\","
                                + "\"network\":\"BEP20\",\"qrFileId\":\""
                                + used
                                + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(body -> json.readTree(body).at("/data/id").asText()));
    long secondFile = uploadQr(kol, "again.png", PNG);
    mvc.perform(
            put("/kol-api/payout-accounts/" + account)
                .header(KOL_TOKEN, other.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"USDT\",\"account\":\"BobWallet456\","
                        + "\"network\":\"BEP20\",\"qrFileId\":\""
                        + used
                        + "\"}"))
        .andExpect(status().isOk());
    // Keeping the same file on edit is fine; another account cannot claim it afterwards.
    mvc.perform(
            post("/kol-api/payout-accounts")
                .header(KOL_TOKEN, other.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountType\":\"USDT\",\"account\":\"BobWallet789\","
                        + "\"network\":\"SOL\",\"qrFileId\":\""
                        + used
                        + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("FILE_IN_USE"));
    assertThat(secondFile).isPositive();
  }

  @Test
  void shouldScopeToTheCurrentBdAndCompany() throws Exception {
    long bd1 = staff("bd-one", "OWN", "payout-account:review", "kol:edit");
    long bd2 = staff("bd-two", "OWN", "payout-account:review", "kol:edit");
    String one = loginStaff("bd-one");
    String two = loginStaff("bd-two");
    LoggedKol kol = kol("Dave", "dave@example.com", bd1);
    long accountId = createPaypal(kol, "dave@example.com");

    // Before the transfer only the owning BD reaches the account.
    mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, two))
        .andExpect(jsonPath("$.data.total").value(0));
    mvc.perform(get("/admin-api/commission/payout-accounts/" + accountId).header(ADMIN_TOKEN, two))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/commission/payout-accounts/" + accountId).header(ADMIN_TOKEN, one))
        .andExpect(status().isOk());

    // The super admin can transfer the KOL; visibility follows the current assignment.
    mvc.perform(
            put("/admin-api/kol/kols/" + kol.id() + "/bd-user")
                .header(ADMIN_TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bdUserId\":\"" + bd2 + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, one))
        .andExpect(jsonPath("$.data.total").value(0));
    mvc.perform(get("/admin-api/commission/payout-accounts/" + accountId).header(ADMIN_TOKEN, one))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/commission/payout-accounts/" + accountId).header(ADMIN_TOKEN, two))
        .andExpect(status().isOk());

    // A read-only funds viewer lists but cannot open details; without the codes nothing is visible.
    staff("funds-view", "ALL", "commission:view");
    String viewer = loginStaff("funds-view");
    mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(
            get("/admin-api/commission/payout-accounts/" + accountId).header(ADMIN_TOKEN, viewer))
        .andExpect(status().isForbidden());
    staff("plain-op", "ALL", "kol:view");
    String plain = loginStaff("plain-op");
    mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, plain))
        .andExpect(status().isForbidden());

    // Another company's account is invisible even to the company super admin.
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "pay-co", "pay-admin");
    LoggedKol foreign = companyKol(other.tenantId(), "pay-admin", "dave@example.com");
    long foreignAccount =
        Long.parseLong(
            mvc.perform(
                    post("/kol-api/payout-accounts")
                        .header(KOL_TOKEN, foreign.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"accountType\":\"PAYPAL\",\"account\":\"dave@example.com\","
                                + "\"holderName\":\"Dave\",\"currency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(body -> json.readTree(body).at("/data/id").asText()));
    assertThat(foreignAccount).isPositive();
    mvc.perform(
            get("/admin-api/commission/payout-accounts/" + foreignAccount)
                .header(ADMIN_TOKEN, admin))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolCode=" + kol.kolCode())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(1));
    // Same email, two companies: the second company sees its own account only.
    mvc.perform(get("/admin-api/commission/payout-accounts").header(ADMIN_TOKEN, other.token()))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  /** KOL created under another company by that company's super admin. */
  private LoggedKol companyKol(long tenantId, String nickname, String email) throws Exception {
    KolDO kol = new KolDO();
    kol.setName(nickname);
    kol.setEmail(email);
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setStatus(1);
    TenantScope.runCompany(tenantId, () -> writer.insert(kol, null, 1L));
    return activated(new LoggedKol(kol.getId(), kol.getKolCode(), null), email);
  }

  @Test
  void shouldSetDefaultRightAfterCreatingTwoAccounts() throws Exception {
    LoggedKol kol = kol("Finn", "finn@example.com", null);
    long qrFile = uploadQr(kol, "finn.png", PNG);
    long paypalId = createPaypal(kol, "finn.pay@example.com");
    long usdtId =
        Long.parseLong(
            mvc.perform(
                    post("/kol-api/payout-accounts")
                        .header(KOL_TOKEN, kol.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"accountType\":\"USDT\",\"account\":\"TQ5xnPW9tZ3kA7qL9bb\","
                                + "\"network\":\"TRC20\",\"qrFileId\":\""
                                + qrFile
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(body -> json.readTree(body).at("/data/id").asText()));

    mvc.perform(
            put("/kol-api/payout-accounts/" + paypalId + "/default").header(KOL_TOKEN, kol.token()))
        .andExpect(status().isOk());
    mvc.perform(
            put("/kol-api/payout-accounts/" + usdtId + "/default").header(KOL_TOKEN, kol.token()))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM crm_payout_account WHERE kol_id = ? AND is_default",
                Long.class,
                kol.id()))
        .containsExactly(usdtId);
  }

  @Test
  void shouldListEveryStatusAndFilterByKolId() throws Exception {
    long bd = staff("bd-list", "OWN", "payout-account:review", "kol:edit");
    String bdToken = loginStaff("bd-list");
    LoggedKol mine = kol("Gina", "gina@example.com", bd);
    LoggedKol other = kol("Hugo", "hugo@example.com", null);
    long ginaPaypal = createPaypal(mine, "gina@example.com");
    createPaypal(mine, "gina2@example.com");
    createPaypal(other, "hugo@example.com");
    // Disabled accounts stay in the admin list: no status filter is applied by default.
    mvc.perform(
            put("/kol-api/payout-accounts/" + ginaPaypal + "/disable")
                .header(KOL_TOKEN, mine.token()))
        .andExpect(status().isOk());

    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolId=" + mine.id())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(
            jsonPath("$.data.items[?(@.id == '" + ginaPaypal + "')].status").value("DISABLED"));
    mvc.perform(
            get("/admin-api/commission/payout-accounts?status=ALL&kolCode=" + mine.kolCode())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(2));
    mvc.perform(
            get("/admin-api/commission/payout-accounts?status=DISABLED&kolId=" + mine.id())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(ginaPaypal)));
    mvc.perform(
            get("/admin-api/commission/payout-accounts?status=APPROVED&kolId=" + mine.id())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolId=" + other.id())
                .header(ADMIN_TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(1));

    // An OWN-scope BD gets an empty list for a KOL that is not assigned to them.
    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolId=" + other.id())
                .header(ADMIN_TOKEN, bdToken))
        .andExpect(jsonPath("$.data.total").value(0));
    mvc.perform(
            get("/admin-api/commission/payout-accounts?kolId=" + mine.id())
                .header(ADMIN_TOKEN, bdToken))
        .andExpect(jsonPath("$.data.total").value(2));
  }
}
