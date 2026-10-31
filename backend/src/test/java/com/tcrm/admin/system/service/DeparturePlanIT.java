package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.Captchas;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.mapper.SysUserDeparturePlanMapper;
import com.tcrm.admin.system.mapper.SysUserTenantMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 41: the plan APIs, the due job, the hand-over it delegates to the creator module, and what
 * an executed plan leaves behind. Real migrations, tenant filtering, row locks and events.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class DeparturePlanIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String USERS = "/admin-api/system/users";
  private static final String SELF = "/admin-api/auth/resignation";

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
  private final ApplicationContext context;
  private String admin;
  private long bdA;
  private String bdAToken;
  private long bdB;
  private String bdBToken;

  @Autowired
  DeparturePlanIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, ApplicationContext context) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.context = context;
  }

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_notification");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM crm_payout_account");
    jdbc.update("DELETE FROM crm_kol_bd_transfer");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM sys_user_departure_plan");
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    bdA = CompanySessions.createStaff(mvc, json, admin, PASSWORD, "t41_bd_a", "BD", "OWN", null);
    bdB = CompanySessions.createStaff(mvc, json, admin, PASSWORD, "t41_bd_b", "BD", "OWN", null);
    bdAToken = CompanySessions.login(mvc, json, "t41_bd_a", PASSWORD);
    bdBToken = CompanySessions.login(mvc, json, "t41_bd_b", PASSWORD);
  }

  @Test
  void shouldMigrateWithIndexesAndComments_whenStarting() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('202610221400', '202610221401') AND success",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'sys_user_departure_plan'",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a WHERE a.attrelid = 'sys_user_departure_plan'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NULL",
                String.class))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_crm_notification_type'",
                String.class))
        .contains("DEPARTURE_PLAN_SUBMITTED", "DEPARTURE_PLAN_EXECUTED");
  }

  @Test
  void shouldRecordAndWithdrawOwnResignation_whenBdSubmitsIt() throws Exception {
    mvc.perform(get(SELF).header(TOKEN, bdAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist());

    JsonNode plan = submitOwn(bdAToken, future(), "family");
    assertThat(plan.get("type").asText()).isEqualTo("RESIGN");
    assertThat(plan.get("initiator").asText()).isEqualTo("SELF");
    assertThat(plan.get("status").asText()).isEqualTo("SCHEDULED");
    assertThat(plan.get("awaitingHeir").asBoolean()).isTrue();
    assertThat(plan.get("heirUserId").isNull()).isTrue();

    mvc.perform(get(SELF).header(TOKEN, bdAToken))
        .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.data.type").value("RESIGN"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_notification WHERE type = 'DEPARTURE_PLAN_SUBMITTED'",
                Integer.class))
        .isEqualTo(1);

    mvc.perform(delete(SELF).header(TOKEN, bdAToken)).andExpect(status().isOk());
    mvc.perform(get(SELF).header(TOKEN, bdAToken)).andExpect(jsonPath("$.data").doesNotExist());
    expectError(delete(SELF).header(TOKEN, bdAToken), 404, "COMMON_NOT_FOUND");
  }

  @Test
  void shouldKeepResignationToBdAccountsAndTheFuture_whenSubmittingOwn() throws Exception {
    CompanySessions.createStaff(
        mvc, json, admin, PASSWORD, "t41_operator", "OPERATOR", "ALL", null);
    String operatorToken = CompanySessions.login(mvc, json, "t41_operator", PASSWORD);
    expectError(
        post(SELF)
            .header(TOKEN, operatorToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("plannedLeaveAt", future().toString()))),
        422,
        "DEPARTURE_NOT_BD_ACCOUNT");

    expectError(
        post(SELF)
            .header(TOKEN, bdAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of("plannedLeaveAt", Instant.now().minusSeconds(60).toString()))),
        422,
        "DEPARTURE_INVALID_TIME");

    submitOwn(bdAToken, future(), null);
    expectError(
        post(SELF)
            .header(TOKEN, bdAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("plannedLeaveAt", future().toString()))),
        409,
        "DEPARTURE_PLAN_EXISTS");
  }

  @Test
  void shouldScheduleEditAndCancel_whenAdministratorPlans() throws Exception {
    JsonNode created = createPlan(bdA, "DISABLE", future(), bdB, "handover");
    assertThat(created.get("initiator").asText()).isEqualTo("ADMIN");
    assertThat(created.get("status").asText()).isEqualTo("SCHEDULED");
    assertThat(created.get("heirUserId").asText()).isEqualTo(String.valueOf(bdB));
    assertThat(created.get("heirName").asText()).isEqualTo("t41_bd_b");
    assertThat(created.get("awaitingHeir").asBoolean()).isFalse();

    JsonNode edited =
        readData(
            mvc.perform(
                    put(USERS + "/" + bdA + "/departure-plan")
                        .header(TOKEN, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(planBody("DISABLE", future(), null, null))))
                .andExpect(status().isOk()));
    assertThat(edited.get("heirUserId").isNull()).isTrue();
    assertThat(edited.get("awaitingHeir").asBoolean()).isTrue();

    mvc.perform(delete(USERS + "/" + bdA + "/departure-plan").header(TOKEN, admin))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM sys_user_departure_plan WHERE user_id = ?", String.class, bdA))
        .isEqualTo("CANCELLED");
    expectError(
        delete(USERS + "/" + bdA + "/departure-plan").header(TOKEN, admin),
        409,
        "DEPARTURE_PLAN_NOT_SCHEDULED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE result = 'SUCCESS' AND action IN ('Schedule departure', 'Edit departure plan', 'Cancel departure plan')",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void shouldRejectHeirsThatFailAnyRule_whenScheduling() throws Exception {
    expectError(
        post(USERS + "/" + bdA + "/departure-plan")
            .header(TOKEN, admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(planBody("RESIGN", future(), bdA, null))),
        422,
        "DEPARTURE_INVALID_HEIR");
    expectError(
        post(USERS + "/" + bdA + "/departure-plan")
            .header(TOKEN, admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(planBody("RESIGN", future(), 1L, null))),
        422,
        "DEPARTURE_INVALID_HEIR");

    createPlan(bdB, "RESIGN", future(), null, null);
    expectError(
        post(USERS + "/" + bdA + "/departure-plan")
            .header(TOKEN, admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(planBody("RESIGN", future(), bdB, null))),
        422,
        "DEPARTURE_INVALID_HEIR");

    // The picker hides the account itself and every BD that already has a plan.
    JsonNode heirs =
        readData(
            mvc.perform(
                    get(USERS + "/departure-heirs")
                        .param("excludeUserId", "" + bdA)
                        .header(TOKEN, admin))
                .andExpect(status().isOk()));
    assertThat(heirs).isEmpty();
  }

  @Test
  void shouldHandOverCreatorsThenDisable_whenPlanBecomesDue() throws Exception {
    kol(11L, bdA);
    kol(12L, bdA);
    kol(13L, bdB);
    Instant effective = future();
    long planId = planId(createPlan(bdA, "RESIGN", effective, bdB, "leaving"));

    runJob(Instant.now().plusSeconds(7200));

    assertThat(owner(11L)).isEqualTo(bdB);
    assertThat(owner(12L)).isEqualTo(bdB);
    assertThat(owner(13L)).isEqualTo(bdB);
    assertThat(memberStatus(bdA)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || '/' || result FROM sys_user_departure_plan WHERE id = ?",
                String.class,
                planId))
        .isEqualTo("DONE/TRANSFERRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_transfer WHERE status = 'DONE' AND from_bd_user_id = ? AND to_bd_user_id = ?",
                Integer.class,
                bdA,
                bdB))
        .isEqualTo(2);
    assertThat(openHistoryStart(11L)).isEqualTo(effective);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = ? AND end_time IS NULL",
                Integer.class,
                11L))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_notification WHERE type = 'DEPARTURE_PLAN_EXECUTED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE action = 'Execute departure plan'",
                Integer.class))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE action = 'Hand over departing BD KOLs'",
                Integer.class))
        .isPositive();
  }

  @Test
  void shouldUnassignCreators_whenPlanHasNoHeir() throws Exception {
    kol(21L, bdA);
    long planId = planId(createPlan(bdA, "DISABLE", future(), null, null));

    runJob(Instant.now().plusSeconds(7200));

    assertThat(owner(21L)).isNull();
    assertThat(memberStatus(bdA)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || '/' || result FROM sys_user_departure_plan WHERE id = ?",
                String.class,
                planId))
        .isEqualTo("DONE/UNASSIGNED");
  }

  @Test
  void shouldDisableWithoutHandingOver_whenHeirBecameUnusable() throws Exception {
    kol(31L, bdA);
    long planId = planId(createPlan(bdA, "RESIGN", future(), bdB, null));
    mvc.perform(
            put(USERS + "/" + bdB + "/status")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());

    runJob(Instant.now().plusSeconds(7200));

    assertThat(owner(31L)).isNull();
    assertThat(memberStatus(bdA)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || '/' || result FROM sys_user_departure_plan WHERE id = ?",
                String.class,
                planId))
        .isEqualTo("DONE/HEIR_INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT result_message FROM sys_user_departure_plan WHERE id = ?",
                String.class,
                planId))
        .contains("不可用");
  }

  @Test
  void shouldNotMoveCreatorsTwice_whenTheJobRunsAgain() throws Exception {
    kol(41L, bdA);
    createPlan(bdA, "RESIGN", future(), bdB, null);
    runJob(Instant.now().plusSeconds(7200));
    int history = countHistory(41L);
    int transfers = countTransfers();

    runJob(Instant.now().plusSeconds(7201));

    assertThat(countHistory(41L)).isEqualTo(history);
    assertThat(countTransfers()).isEqualTo(transfers);
    assertThat(owner(41L)).isEqualTo(bdB);
  }

  @Test
  void shouldHandOverInTheSameRequest_whenPlanIsImmediate() throws Exception {
    kol(51L, bdA);

    JsonNode plan = createPlan(bdA, "RESIGN", null, bdB, null);

    assertThat(plan.get("status").asText()).isEqualTo("DONE");
    assertThat(plan.get("result").asText()).isEqualTo("TRANSFERRED");
    assertThat(owner(51L)).isEqualTo(bdB);
    assertThat(memberStatus(bdA)).isZero();
  }

  @Test
  void shouldEndOldSessionsAndBlockSignIn_whenAccountDisabled() throws Exception {
    createPlan(bdA, "DISABLE", null, null, null);

    int status =
        mvc.perform(get("/admin-api/kol/kols").header(TOKEN, bdAToken))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isIn(401, 403);
    int login =
        mvc.perform(
                post("/admin-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Captchas.loginBody(mvc, json, "t41_bd_a", PASSWORD))))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(login).isEqualTo(401);
  }

  @Test
  void shouldShowPayoutAccountToHeir_whenCreatorsHandedOver() throws Exception {
    kol(61L, bdA);
    payoutAccount(61L);

    assertThat(payoutAccounts(bdAToken)).isEqualTo(1);
    createPlan(bdA, "RESIGN", null, bdB, null);

    assertThat(payoutAccounts(bdBToken)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_payout_account WHERE kol_id = 61 AND status = 'APPROVED'",
                Integer.class))
        .isEqualTo(1);
  }

  private DepartureService runner(Instant now) {
    return new DepartureService(
        context.getBean(SysUserDeparturePlanMapper.class),
        context.getBean(SysUserTenantMapper.class),
        context.getBean(BdUserService.class),
        context.getBean(UserService.class),
        context.getBean(CurrentUserContext.class),
        context.getBean(ExternalAuditApi.class),
        context.getBean(AuditSanitizer.class),
        context,
        context.getBean(TransactionTemplate.class),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private void runJob(Instant now) {
    TenantScope.runCompany(1L, () -> runner(now).executeDueBatch());
  }

  private Instant future() {
    return Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
  }

  private Map<String, Object> planBody(String type, Instant effective, Long heir, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    if (effective != null) {
      body.put("effectiveAt", effective.toString());
    }
    body.put("heirUserId", heir);
    body.put("reason", reason);
    return body;
  }

  private static long planId(JsonNode plan) {
    return Long.parseLong(plan.get("id").asText());
  }

  private JsonNode createPlan(long userId, String type, Instant effective, Long heir, String reason)
      throws Exception {
    return readData(
        mvc.perform(
                post(USERS + "/" + userId + "/departure-plan")
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(planBody(type, effective, heir, reason))))
            .andExpect(status().isOk()));
  }

  private JsonNode submitOwn(String token, Instant plannedLeaveAt, String reason) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("plannedLeaveAt", plannedLeaveAt.toString());
    body.put("reason", reason);
    return readData(
        mvc.perform(
                post(SELF)
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk()));
  }

  private JsonNode readData(ResultActions actions) throws Exception {
    return json.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
  }

  private void expectError(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    mvc.perform(request)
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.errorCode").value(code));
  }

  private int payoutAccounts(String token) throws Exception {
    return Integer.parseInt(
        json.readTree(
                mvc.perform(get("/admin-api/commission/payout-accounts").header(TOKEN, token))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/total")
            .asText());
  }

  private void kol(long id, long bd) {
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, bd_user_id, create_time, update_time) VALUES (?, 1, ?, 'Test', ?, 'US', ?, now(), now())",
        id,
        "KOL-AAA" + String.format("%03d", id),
        "test" + id + "@example.test",
        bd);
    jdbc.update(
        "INSERT INTO crm_kol_bd_history (id, tenant_id, kol_id, bd_user_id, start_time, create_time, update_time) VALUES (?, 1, ?, ?, now() - INTERVAL '1 day', now(), now())",
        1L + id,
        id,
        bd);
  }

  private void payoutAccount(long kolId) {
    jdbc.update(
        "INSERT INTO crm_payout_account (id, tenant_id, kol_id, account_type, account_cipher, account_masked, holder_name, currency, status, is_default, last_submitted_at, create_time, update_time) VALUES (?, 1, ?, 'PAYPAL', 'v1:test', 'te***@example.test', 'Holder', 'USD', 'APPROVED', FALSE, now(), now(), now())",
        900L + kolId,
        kolId);
  }

  private Long owner(long kolId) {
    return jdbc.queryForObject("SELECT bd_user_id FROM crm_kol WHERE id = ?", Long.class, kolId);
  }

  private int memberStatus(long userId) {
    return jdbc.queryForObject(
        "SELECT status FROM sys_user_tenant WHERE user_id = ?", Integer.class, userId);
  }

  private Instant openHistoryStart(long kolId) {
    return jdbc.queryForObject(
            "SELECT start_time FROM crm_kol_bd_history WHERE kol_id = ? AND end_time IS NULL",
            java.sql.Timestamp.class,
            kolId)
        .toInstant();
  }

  private int countHistory(long kolId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = ?", Integer.class, kolId);
  }

  private int countTransfers() {
    return jdbc.queryForObject("SELECT count(*) FROM crm_kol_bd_transfer", Integer.class);
  }
}
