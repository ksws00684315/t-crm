package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolBdTransferMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Task 16: real migrations, row locks, tenant filtering, BEFORE_COMMIT events and audit. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BdTransferIT {
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

  private static final String BASE = "/admin-api/kol/bd-transfers";
  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ApplicationContext context;
  private String admin;
  private long source;
  private long target;

  @Autowired
  BdTransferIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, ApplicationContext context) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.context = context;
  }

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM crm_kol_bd_transfer");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    source =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, "source-bd", "BD", "OWN", null);
    target =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, "target-bd", "BD", "OWN", null);
    kol(11L, 1L, source);
    kol(12L, 1L, source);
  }

  private void kol(long id, long tenant, long bd) {
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, bd_user_id, create_time, update_time) VALUES (?, ?, ?, 'Test', ?, 'US', ?, now(), now())",
        id,
        tenant,
        "KOL-AAA" + String.format("%03d", id),
        "test" + id + "@example.test",
        bd);
    jdbc.update(
        "INSERT INTO crm_kol_bd_history (id, tenant_id, kol_id, bd_user_id, start_time, create_time, update_time) VALUES (?, ?, ?, ?, now() - INTERVAL '1 day', now(), now())",
        id,
        tenant,
        id,
        bd);
  }

  private Instant future() {
    return Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
  }

  private JsonNode create(Object body) throws Exception {
    var response =
        mvc.perform(
                post(BASE)
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString()).get("data");
  }

  private long schedule(long kolId, Long to, Instant effective) throws Exception {
    create(new BdTransferCommand(List.of(kolId), null, to, effective, "handover"));
    return jdbc.queryForObject(
        "SELECT id FROM crm_kol_bd_transfer WHERE kol_id = ? AND status = 'SCHEDULED'",
        Long.class,
        kolId);
  }

  private BdTransferService runner(Instant now) {
    return new BdTransferService(
        context.getBean(KolMapper.class),
        context.getBean(KolBdHistoryMapper.class),
        context.getBean(KolBdTransferMapper.class),
        context.getBean(BdUserApi.class),
        context.getBean(CurrentUserContext.class),
        context.getBean(TransactionTemplate.class),
        context.getBean(ExternalAuditApi.class),
        context.getBean(AuditSanitizer.class),
        Clock.fixed(now, ZoneOffset.UTC),
        context);
  }

  private void execute(long id, Instant now) {
    TenantScope.runCompany(1L, () -> runner(now).executeDue(id));
  }

  private String state(long id) {
    return jdbc.queryForObject(
        "SELECT status FROM crm_kol_bd_transfer WHERE id = ?", String.class, id);
  }

  private Long owner(long kol) {
    return jdbc.queryForObject("SELECT bd_user_id FROM crm_kol WHERE id = ?", Long.class, kol);
  }

  private void continuous(long kol) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT start_time, lag(end_time) OVER (ORDER BY start_time, create_time, id) AS previous_end FROM crm_kol_bd_history WHERE kol_id = ?) h WHERE previous_end IS NOT NULL AND previous_end <> start_time",
                Integer.class,
                kol))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = ? AND end_time IS NULL",
                Integer.class,
                kol))
        .isEqualTo(1);
  }

  @Test
  void shouldMigrateWithTenantCommentsAndUniqueReservation_whenStarting() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'crm_kol_bd_transfer'",
                Integer.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '202610171800' AND success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '202610172100' AND success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'crm_kol_bd_transfer' AND column_name = 'reason'",
                String.class))
        .isEqualTo("YES");
    assertThat(
            jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a WHERE a.attrelid = 'crm_kol_bd_transfer'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NULL",
                String.class))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'crm_kol_bd_transfer' AND column_name = 'tenant_id'",
                String.class))
        .isEqualTo("NO");
  }

  @Test
  void shouldTransferAllAndSkipSameOwner_whenSelectingBySourceOrIds() throws Exception {
    assertThat(create(Map.of("fromBdUserId", source, "toBdUserId", source)).get("skipped").asInt())
        .isEqualTo(2);
    assertThat(
            create(Map.of("fromBdUserId", source, "toBdUserId", target)).get("immediate").asInt())
        .isEqualTo(2);
    assertThat(owner(11L)).isEqualTo(target);
    assertThat(owner(12L)).isEqualTo(target);
    assertThat(
            create(Map.of("kolIds", List.of(11L, 12L), "toBdUserId", target))
                .get("skipped")
                .asInt())
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_transfer WHERE status = 'DONE'", Integer.class))
        .isEqualTo(2);
    continuous(11L);
    continuous(12L);
  }

  @Test
  void shouldCancelReservationAndRecordDone_whenLegacyPutChangesBd() throws Exception {
    long scheduled = schedule(11L, null, future());
    mvc.perform(
            put("/admin-api/kol/kols/11/bd-user")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("bdUserId", target))))
        .andExpect(status().isOk());
    assertThat(state(scheduled)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT cancel_reason FROM crm_kol_bd_transfer WHERE id = ?",
                String.class,
                scheduled))
        .isEqualTo("被立即转移取代");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_transfer WHERE kol_id = 11 AND status = 'DONE'",
                Integer.class))
        .isEqualTo(1);
    continuous(11L);
  }

  @Test
  void shouldRejectWholeBatchWithCodes_whenAnyKolHasReservation() throws Exception {
    long first = schedule(11L, target, future());
    mvc.perform(
            post(BASE)
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BdTransferCommand(List.of(11L, 12L), null, target, future(), null))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_TRANSFER_EXISTS"))
        .andExpect(jsonPath("$.data[0]").value("KOL-AAA011"));
    assertThat(state(first)).isEqualTo("SCHEDULED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_kol_bd_transfer", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldPreserveAgreedHistoryTimeAndExecuteOnce_whenJobRunsLate() throws Exception {
    Instant effective = future();
    long id = schedule(11L, target, effective);
    execute(id, effective.plusSeconds(90));
    execute(id, effective.plusSeconds(120));
    assertThat(state(id)).isEqualTo("DONE");
    assertThat(owner(11L)).isEqualTo(target);
    assertThat(
            jdbc.queryForObject(
                    "SELECT start_time FROM crm_kol_bd_history WHERE kol_id = 11 AND end_time IS NULL",
                    Timestamp.class)
                .toInstant())
        .isEqualTo(effective);
    assertThat(
            jdbc.queryForObject(
                    "SELECT executed_at FROM crm_kol_bd_transfer WHERE id = ?", Timestamp.class, id)
                .toInstant())
        .isEqualTo(effective.plusSeconds(90));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = 11", Integer.class))
        .isEqualTo(2);
    continuous(11L);
  }

  @Test
  void shouldCancel_whenOwnershipOrTargetChangedBeforeExecution() throws Exception {
    Instant time = future();
    long a = schedule(11L, target, time);
    long b = schedule(12L, target, time);
    jdbc.update("UPDATE crm_kol SET bd_user_id = NULL WHERE id = 11");
    jdbc.update("UPDATE sys_user SET status = 0 WHERE id = ?", target);
    execute(a, time);
    execute(b, time);
    assertThat(state(a)).isEqualTo("CANCELLED");
    assertThat(state(b)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT cancel_reason FROM crm_kol_bd_transfer WHERE id = ?", String.class, a))
        .isEqualTo("执行时归属已变化");
    assertThat(
            jdbc.queryForObject(
                "SELECT cancel_reason FROM crm_kol_bd_transfer WHERE id = ?", String.class, b))
        .isEqualTo("目标商务不可用");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_kol_bd_history", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void shouldClampToCurrentHistoryStart_whenScheduledTimeIsEarlier() throws Exception {
    Instant effective = future();
    long id = schedule(11L, target, effective);
    Instant later = effective.plusSeconds(5);
    jdbc.update(
        "UPDATE crm_kol_bd_history SET start_time = ? WHERE kol_id = 11", Timestamp.from(later));
    execute(id, later.plusSeconds(10));
    assertThat(state(id)).isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                    "SELECT start_time FROM crm_kol_bd_history WHERE kol_id = 11 AND end_time IS NULL",
                    Timestamp.class)
                .toInstant())
        .isEqualTo(later);
    continuous(11L);
  }

  @Test
  void shouldUnassignAtomicallyAndKeepSourceReservation_whenStaffIsDisabled() throws Exception {
    Instant time = future();
    long outgoing = schedule(11L, target, time);
    long incoming = schedule(12L, source, time);
    mvc.perform(
            put("/admin-api/system/users/" + source + "/status")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", 0))))
        .andExpect(status().isOk());
    assertThat(owner(11L)).isNull();
    assertThat(owner(12L)).isNull();
    assertThat(state(outgoing)).isEqualTo("SCHEDULED");
    assertThat(state(incoming)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT from_bd_user_id FROM crm_kol_bd_transfer WHERE id = ?",
                Long.class,
                outgoing))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT cancel_reason FROM crm_kol_bd_transfer WHERE id = ?",
                String.class,
                incoming))
        .isEqualTo("目标商务已停用");
    mvc.perform(
            put("/admin-api/system/users/" + source + "/status")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", 1))))
        .andExpect(status().isOk());
    assertThat(owner(11L)).isNull();
    assertThat(owner(12L)).isNull();
    execute(outgoing, time);
    assertThat(state(outgoing)).isEqualTo("DONE");
    assertThat(owner(11L)).isEqualTo(target);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = 11 AND bd_user_id IS NULL",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE action = 'Unassign disabled staff KOLs' AND request_params::jsonb ->> 'kolCount' = '2'",
                Integer.class))
        .isEqualTo(1);
    continuous(11L);
    continuous(12L);
  }

  @Test
  void shouldCancelAndFilterWithAudit_whenAdminUsesListAndDelete() throws Exception {
    long id = schedule(11L, null, future());
    mvc.perform(
            get(BASE)
                .header(TOKEN, admin)
                .param("kolCode", "KOL-AAA011")
                .param("status", "SCHEDULED")
                .param("fromBdUserId", Long.toString(source)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(id)));
    mvc.perform(delete(BASE + "/" + id).header(TOKEN, admin)).andExpect(status().isOk());
    mvc.perform(delete(BASE + "/" + id).header(TOKEN, admin))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_TRANSFER_NOT_SCHEDULED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE action = 'Create BD transfer'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE action = 'Cancel BD transfer'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldReturnDisplayFieldsAndReason_whenListing() throws Exception {
    jdbc.update("UPDATE sys_user SET nickname = '源商务' WHERE id = ?", source);
    jdbc.update("UPDATE sys_user SET nickname = '' WHERE id = ?", target);
    create(Map.of("kolIds", List.of(11L), "toBdUserId", target, "reason", "离职交接"));
    mvc.perform(
            put("/admin-api/kol/kols/12/bd-user")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("bdUserId", target))))
        .andExpect(status().isOk());
    schedule(12L, null, future());
    mvc.perform(get(BASE).header(TOKEN, admin).param("kolCode", "KOL-AAA011"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL-AAA011"))
        .andExpect(jsonPath("$.data.items[0].kolName").value("Test"))
        .andExpect(jsonPath("$.data.items[0].fromBdUserName").value("源商务"))
        .andExpect(jsonPath("$.data.items[0].toBdUserName").value("target-bd"))
        .andExpect(jsonPath("$.data.items[0].reason").value("离职交接"));
    mvc.perform(get(BASE).header(TOKEN, admin).param("kolCode", "KOL-AAA012"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("2"))
        .andExpect(jsonPath("$.data.items[0].reason").value("handover"))
        .andExpect(
            jsonPath("$.data.items[0].toBdUserName").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.items[1].reason").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.items[1].fromBdUserName").value("源商务"));
  }

  @Test
  void shouldHideOtherCompanyResourcesAndRollbackBatch_whenIdsCrossCompany() throws Exception {
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "other", "other-admin");
    long otherBd =
        CompanySessions.createStaff(
            mvc, json, other.token(), PASSWORD, "other-bd", "BD", "OWN", null);
    kol(13L, other.tenantId(), otherBd);
    mvc.perform(
            post(BASE)
                .header(TOKEN, other.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BdTransferCommand(List.of(13L), null, null, future(), null))))
        .andExpect(status().isOk());
    long otherTransfer =
        jdbc.queryForObject("SELECT id FROM crm_kol_bd_transfer WHERE kol_id = 13", Long.class);
    for (var command :
        List.of(
            new BdTransferCommand(List.of(11L, 13L), null, target, null, null),
            new BdTransferCommand(List.of(11L), null, otherBd, null, null),
            new BdTransferCommand(null, otherBd, target, null, null))) {
      mvc.perform(
              post(BASE)
                  .header(TOKEN, admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(command)))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get(BASE).header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(delete(BASE + "/" + otherTransfer).header(TOKEN, admin))
        .andExpect(status().isNotFound());
    assertThat(owner(11L)).isEqualTo(source);
    assertThat(owner(13L)).isEqualTo(otherBd);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_kol_bd_transfer", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldRejectStaffWithEveryPermission_whenAccessingTransferEndpoints() throws Exception {
    long staff =
        CompanySessions.createStaff(
            mvc, json, admin, PASSWORD, "all-staff", "OPERATOR", "ALL", Permission.codes());
    String token = CompanySessions.login(mvc, json, "all-staff", PASSWORD);
    assertThat(staff).isPositive();
    mvc.perform(
            post(BASE)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kolIds", List.of(11L)))))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE).header(TOKEN, token)).andExpect(status().isForbidden());
    mvc.perform(delete(BASE + "/21").header(TOKEN, token)).andExpect(status().isForbidden());
  }

  @Test
  void shouldSerializeConcurrentRequestsAndKeepHistoryContinuous_whenTwoTransfersRace()
      throws Exception {
    long third =
        CompanySessions.createStaff(mvc, json, admin, PASSWORD, "third-bd", "BD", "OWN", null);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return create(Map.of("kolIds", List.of(11L), "toBdUserId", target));
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return create(Map.of("kolIds", List.of(11L), "toBdUserId", third));
              });
      start.countDown();
      assertThat(a.get(20, TimeUnit.SECONDS).get("immediate").asInt()).isEqualTo(1);
      assertThat(b.get(20, TimeUnit.SECONDS).get("immediate").asInt()).isEqualTo(1);
    }
    assertThat(owner(11L)).isIn(target, third);
    assertThat(
            jdbc.queryForObject(
                "SELECT bd_user_id FROM crm_kol_bd_history WHERE kol_id = 11 AND end_time IS NULL",
                Long.class))
        .isEqualTo(owner(11L));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_kol_bd_history WHERE kol_id = 11", Integer.class))
        .isEqualTo(3);
    continuous(11L);
  }
}
