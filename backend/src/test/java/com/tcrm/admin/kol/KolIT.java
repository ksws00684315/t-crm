package com.tcrm.admin.kol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.Captchas;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.service.KolImportColumn;
import com.tcrm.admin.kol.service.KolWriter;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Task 04 against real PostgreSQL and Redis: migrations, code generation, BD history, import. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolIT {
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

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final KolWriter writer;
  private final javax.sql.DataSource dataSource;

  @Autowired
  KolIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      KolWriter writer,
      javax.sql.DataSource dataSource) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.writer = writer;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void prepareIndependentData() {
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
  }

  @Test
  void shouldCreateTablesSequenceAndIndexes_whenMigrationsRun() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'"
                    + " AND table_name IN ('crm_kol','crm_kol_bd_history','crm_task','crm_task_file')",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=true"
                    + " AND version IN ('202610051900','202610051901')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_class WHERE relkind='S' AND relname='crm_kol_code_seq'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN ('crm_kol','crm_kol_bd_history')",
                String.class))
        .contains(
            "uk_crm_kol_kol_code",
            "uk_crm_kol_email",
            "idx_crm_kol_create_time",
            "idx_crm_kol_bd_user",
            "uk_crm_kol_bd_history_current");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO crm_kol (id, kol_code, name, email, country, tenant_id, create_by,"
                        + " create_time, update_by, update_time)"
                        + " VALUES (1, 'KOL12', 'x', 'x@x.io', 'US', 1, 1, now(), 1, now())"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("ck_crm_kol_code");
  }

  @Test
  void shouldIssueUniqueCodesAndSkipTakenOnes_whenCreatingConcurrently() throws Exception {
    long next = jdbc.queryForObject("SELECT last_value + 1 FROM crm_kol_code_seq", Long.class);
    // An imported code occupying an upcoming sequence number must be skipped, not reused.
    TenantScope.runCompany(
        1L, () -> writer.insert(kol("taken@x.io"), String.format("KOL-ZZZ%03d", next + 2), 1L));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<String>> jobs = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        String email = "c" + i + "@conc.io";
        jobs.add(
            () ->
                TenantScope.callCompany(
                    1L, () -> writer.insert(kol(email), null, 1L).getKolCode()));
      }
      Set<String> codes = new HashSet<>();
      for (Future<String> f : pool.invokeAll(jobs)) {
        codes.add(f.get());
      }
      assertThat(codes).hasSize(40).doesNotContain(String.format("KOL-ZZZ%03d", next + 2));
      assertThat(codes).allMatch(code -> code.matches("KOL-[A-Z]{3}[0-9]{3,8}"));
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT kol_code) FROM crm_kol", Integer.class))
        .isEqualTo(41);
  }

  @Test
  void shouldKeepOneCurrentPeriod_whenBdChangesRepeatedly() throws Exception {
    String admin = companyAdmin();
    String bd1 = createBd(admin, "bd-one");
    String bd2 = createBd(admin, "bd-two");
    String kolId =
        json.readTree(
                mvc.perform(
                        post("/admin-api/kol/kols")
                            .header(TOKEN, admin)
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"Alice\",\"email\":\"Alice@Example.com\","
                                    + "\"country\":\"us\",\"bdUserId\":\""
                                    + bd1
                                    + "\",\"defaultRate\":\"0.155\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                    .andExpect(jsonPath("$.data.defaultRate").value("0.1550"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/id")
            .asText();

    for (String target : new String[] {"\"" + bd2 + "\"", "null", "null", "\"" + bd1 + "\""}) {
      mvc.perform(
              put("/admin-api/kol/kols/" + kolId + "/bd-user")
                  .header(TOKEN, admin)
                  .contentType("application/json")
                  .content("{\"bdUserId\":" + target + "}"))
          .andExpect(status().isOk());
    }

    List<Map<String, Object>> periods =
        jdbc.queryForList(
            "SELECT bd_user_id, start_time, end_time FROM crm_kol_bd_history WHERE kol_id = ?"
                + " ORDER BY start_time, id",
            Long.valueOf(kolId));
    // Initial bd1, then bd2, unassigned, bd1 again; the repeated "null" is a no-op.
    assertThat(periods).hasSize(4);
    assertThat(periods)
        .extracting(p -> p.get("bd_user_id") == null ? null : String.valueOf(p.get("bd_user_id")))
        .containsExactly(bd1, bd2, null, bd1);
    assertThat(periods.stream().filter(p -> p.get("end_time") == null)).hasSize(1);
    for (int i = 1; i < periods.size(); i++) {
      assertThat((Timestamp) periods.get(i - 1).get("end_time"))
          .isEqualTo((Timestamp) periods.get(i).get("start_time"));
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO crm_kol_bd_history (id, kol_id, bd_user_id, start_time, tenant_id,"
                        + " create_by, create_time, update_by, update_time)"
                        + " VALUES (99, ?, NULL, ?, 1, 1, now(), 1, now())",
                    Long.valueOf(kolId),
                    Timestamp.from(Instant.now())))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_crm_kol_bd_history_current");

    // A BD sees only their KOLs: 403 for another BD's KOL, the list filtered to their own.
    String bd2Token = login("bd-two", PASSWORD);
    mvc.perform(get("/admin-api/kol/kols/" + kolId).header(TOKEN, bd2Token))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/kol/kols").param("bdUserId", bd1).header(TOKEN, bd2Token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/kol/kols/" + kolId).header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bdHistory.length()").value(4))
        .andExpect(jsonPath("$.data.bdHistory[0].bdUserName").value("bd-one"))
        .andExpect(jsonPath("$.data.bdHistory[0].endTime").doesNotExist());
  }

  @Test
  void shouldRejectBdEdit_whenKolIsTransferredToAnotherBdMeanwhile() throws Exception {
    String admin = companyAdmin();
    String bd1 = createBd(admin, "bd-one");
    String bd2 = createBd(admin, "bd-two");
    String kolId =
        json.readTree(
                mvc.perform(
                        post("/admin-api/kol/kols")
                            .header(TOKEN, admin)
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"Alice\",\"email\":\"alice@example.com\","
                                    + "\"country\":\"US\",\"bdUserId\":\""
                                    + bd1
                                    + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/id")
            .asText();
    String bd1Token = login("bd-one", PASSWORD);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (var lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.prepareStatement("SELECT id FROM crm_kol WHERE id=? FOR UPDATE")) {
        statement.setLong(1, Long.parseLong(kolId));
        statement.executeQuery().close();
      }
      // The transfer to bd2 queues first; bd1's edit has already passed its scope check.
      var transfer =
          pool.submit(
              () ->
                  mvc.perform(
                          put("/admin-api/kol/kols/" + kolId + "/bd-user")
                              .header(TOKEN, admin)
                              .contentType("application/json")
                              .content("{\"bdUserId\":\"" + bd2 + "\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitLockWaiters(1);
      var edit =
          pool.submit(
              () ->
                  mvc.perform(
                          put("/admin-api/kol/kols/" + kolId)
                              .header(TOKEN, bd1Token)
                              .contentType("application/json")
                              .content(
                                  "{\"name\":\"Changed by bd1\",\"email\":\"alice@example.com\","
                                      + "\"country\":\"US\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitLockWaiters(2);
      lock.commit();
      assertThat(transfer.get()).isEqualTo(200);
      assertThat(edit.get()).isEqualTo(403);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM crm_kol WHERE id=?", String.class, Long.valueOf(kolId)))
        .isEqualTo("Alice");
  }

  private void awaitLockWaiters(int expected) {
    TestWait.until(
        java.time.Duration.ofSeconds(5),
        java.time.Duration.ofMillis(25),
        () -> {
          Integer waiting =
              jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE NOT granted", Integer.class);
          return waiting != null && waiting >= expected;
        },
        "Expected " + expected + " requests waiting for the row lock");
  }

  @Test
  void shouldImportPartiallyAndServeErrorFile_whenWorkbookHasBadRows() throws Exception {
    String admin = companyAdmin();
    createBd(admin, "bd-import");
    TenantScope.runCompany(1L, () -> writer.insert(kol("existing@x.io"), "KOL-ZZZ900", 1L));
    List<List<String>> rows =
        List.of(
            row("", "Dave", "Dave@X.io", "de", "BD-IMPORT", "12.5", "YouTube", "@dave", "1,200"),
            row("KOL-ZZZ500", "Eve", "eve@x.io", "JP"),
            row("kol-zzz500", "Eve Again", "EVE@x.io", "JP"),
            row("KOL-ZZZ900", "Frank", "existing@x.io", "XX"),
            row("", "Gina", "gina@x.io", "US", "", "", "", "", "", "=1+1"));
    MockMultipartFile file =
        new MockMultipartFile("file", "达人导入.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook(rows));

    String taskId =
        json.readTree(
                mvc.perform(
                        multipart("/admin-api/kol/kol-imports")
                            .file(file)
                            .header(TOKEN, admin)
                            .header("Accept-Language", "zh-CN"))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/taskId")
            .asText();

    var task = waitForTask(admin, taskId);
    assertThat(task.at("/status").asText()).isEqualTo("PARTIAL");
    assertThat(task.at("/total").asInt()).isEqualTo(5);
    assertThat(task.at("/done").asInt()).isEqualTo(5);
    assertThat(task.at("/failed").asInt()).isEqualTo(2);
    assertThat(task.at("/hasErrorFile").asBoolean()).isTrue();

    assertThat(
            jdbc.queryForList(
                "SELECT kol_code FROM crm_kol WHERE email IN ('dave@x.io','eve@x.io','gina@x.io')",
                String.class))
        .hasSize(3)
        .contains("KOL-ZZZ500");
    assertThat(
            jdbc.queryForObject(
                "SELECT default_rate::text || '|' || country || '|' || (social_accounts->0->>'followers')"
                    + " FROM crm_kol WHERE email = 'dave@x.io'",
                String.class))
        .isEqualTo("0.1250|DE|1200");

    byte[] errors =
        mvc.perform(get("/admin-api/task/tasks/" + taskId + "/error-file").header(TOKEN, admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    List<Map<Integer, String>> sheet =
        FastExcel.read(new ByteArrayInputStream(errors)).sheet(0).headRowNumber(0).doReadSync();
    assertThat(sheet).hasSize(3);
    assertThat(sheet.get(0).get(14)).isEqualTo("错误原因");
    assertThat(sheet.get(1).get(1)).isEqualTo("Eve Again");
    assertThat(sheet.get(1).get(14)).isEqualTo("达人编号在文件中重复；邮箱在文件中重复");
    assertThat(sheet.get(2).get(14)).startsWith("达人编号已存在；邮箱已存在；国家应为");

    // Only the submitter or a super administrator may read the task.
    createBd(admin, "bd-other");
    String other = login("bd-other", PASSWORD);
    mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, other))
        .andExpect(status().isForbidden());
  }

  private tools.jackson.databind.JsonNode waitForTask(String token, String taskId)
      throws Exception {
    tools.jackson.databind.JsonNode[] holder = new tools.jackson.databind.JsonNode[1];
    TestWait.until(
        java.time.Duration.ofSeconds(30),
        java.time.Duration.ofMillis(200),
        () -> {
          try {
            var task =
                json.readTree(
                        mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, token))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .at("/data");
            String status = task.at("/status").asText();
            if (!"WAITING".equals(status) && !"RUNNING".equals(status)) {
              holder[0] = task;
              return true;
            }
            return false;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        "task " + taskId + " did not finish");
    return holder[0];
  }

  @Test
  void shouldServeEnglishErrorFile_whenImportWasSubmittedInEnglish() throws Exception {
    String admin = companyAdmin();
    List<List<String>> rows =
        List.of(row("BAD!", "Dave", "dave@x.io", "US"), row("", "Eve", "eve@x.io", "US"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out)
        .head(
            com.tcrm.admin.TestTemplates.render(
                    KolImportColumn.headerKeys(), java.util.Locale.ENGLISH)
                .stream()
                .map(List::of)
                .toList())
        .sheet("Sheet1")
        .doWrite(rows);
    byte[] content = out.toByteArray();
    MockMultipartFile file =
        new MockMultipartFile("file", "kols.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, content);

    String taskId =
        json.readTree(
                mvc.perform(
                        multipart("/admin-api/kol/kol-imports")
                            .file(file)
                            .header(TOKEN, admin)
                            .header("Accept-Language", "en-US"))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/taskId")
            .asText();
    var task = waitForTask(admin, taskId);
    assertThat(task.at("/status").asText()).isEqualTo("PARTIAL");

    byte[] errors =
        mvc.perform(get("/admin-api/task/tasks/" + taskId + "/error-file").header(TOKEN, admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    List<Map<Integer, String>> sheet =
        FastExcel.read(new java.io.ByteArrayInputStream(errors))
            .sheet(0)
            .headRowNumber(0)
            .doReadSync();
    assertThat(sheet.get(0).get(14)).isEqualTo("Error");
    assertThat(sheet.get(1).get(14))
        .isEqualTo(
            "Invalid KOL code; it must be KOL- followed by 3 uppercase letters and 3 to 8 digits");
  }

  /** Task 11 §4.3: files built with the pre-task-11 ten-column template keep importing. */
  @Test
  void shouldImportLegacyTenColumnFiles() throws Exception {
    String admin = companyAdmin();
    List<List<String>> rows =
        List.of(
            new ArrayList<>(List.of("", "Hank", "hank@x.io", "US")),
            new ArrayList<>(List.of("", "Ivy", "ivy@x.io", "")));
    rows.forEach(
        row -> row.addAll(Collections.nCopies(KolImportColumn.LEGACY_COLUMNS - row.size(), "")));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out)
        .head(
            com.tcrm.admin.TestTemplates.render(KolImportColumn.legacyHeaderKeys()).stream()
                .map(List::of)
                .toList())
        .sheet("Sheet1")
        .doWrite(rows);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "old.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, out.toByteArray());

    String taskId =
        json.readTree(
                mvc.perform(multipart("/admin-api/kol/kol-imports").file(file).header(TOKEN, admin))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/taskId")
            .asText();

    var task = waitForTask(admin, taskId);
    assertThat(task.at("/status").asText()).isEqualTo("SUCCESS");
    assertThat(
            jdbc.queryForList(
                "SELECT country FROM crm_kol WHERE email IN ('hank@x.io','ivy@x.io') ORDER BY email",
                String.class))
        .containsExactly("US", null);
  }

  /**
   * Task 11 §4.3: the four new columns import; the template code must be usable in this company.
   */
  @Test
  void shouldImportNewProfileColumns_withAgentTemplateCode() throws Exception {
    String admin = companyAdmin();
    long templateId =
        Long.parseLong(
            json.readTree(
                    mvc.perform(
                            post("/admin-api/link/templates")
                                .header(TOKEN, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    "{\"templateCode\":\"kakobuy\",\"templateName\":\"kakobuy\","
                                        + "\"templateUrl\":\"https://item.kakobuy.com/i/{PRODUCT_ID}\","
                                        + "\"sortOrder\":0}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .at("/data/id")
                .asText());
    List<List<String>> rows =
        List.of(
            row(
                "",
                "Jack",
                "jack@x.io",
                "US",
                "",
                "",
                "",
                "",
                "",
                "",
                "+12345678",
                "kakobuy",
                "aff_01",
                "https://docs.google.com/spreadsheets/d/1/edit"),
            row("", "Kate", "kate@x.io", "US", "", "", "", "", "", "", "+12345678", "ghost"));

    String taskId =
        json.readTree(
                mvc.perform(
                        multipart("/admin-api/kol/kol-imports")
                            .file(
                                new MockMultipartFile(
                                    "file",
                                    "new.xlsx",
                                    ImportTaskApi.XLSX_CONTENT_TYPE,
                                    workbook(rows)))
                            .header(TOKEN, admin)
                            .header("Accept-Language", "zh-CN"))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/taskId")
            .asText();

    var task = waitForTask(admin, taskId);
    assertThat(task.at("/status").asText()).isEqualTo("PARTIAL");
    assertThat(task.at("/failed").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT whatsapp || '|' || link_template_id || '|' || invite_code || '|' ||"
                    + " coalesce(sheet_url, '') FROM crm_kol WHERE email = 'jack@x.io'",
                String.class))
        .isEqualTo(
            "+12345678|" + templateId + "|aff_01|https://docs.google.com/spreadsheets/d/1/edit");

    byte[] errors =
        mvc.perform(get("/admin-api/task/tasks/" + taskId + "/error-file").header(TOKEN, admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    List<Map<Integer, String>> sheet =
        FastExcel.read(new java.io.ByteArrayInputStream(errors))
            .sheet(0)
            .headRowNumber(0)
            .doReadSync();
    assertThat(sheet.get(1).get(11)).isEqualTo("ghost");
    assertThat(sheet.get(1).get(14)).isEqualTo("代购平台编码不存在或已停用");
  }

  private static List<String> row(String... cells) {
    List<String> row = new ArrayList<>(List.of(cells));
    row.addAll(Collections.nCopies(KolImportColumn.values().length - cells.length, ""));
    return row;
  }

  private static byte[] workbook(List<List<String>> rows) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out)
        .head(
            com.tcrm.admin.TestTemplates.render(KolImportColumn.headerKeys()).stream()
                .map(List::of)
                .toList())
        .sheet("Sheet1")
        .doWrite(rows);
    return out.toByteArray();
  }

  private static KolDO kol(String email) {
    KolDO kol = new KolDO();
    kol.setName("Concurrent");
    kol.setEmail(email);
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setStatus(1);
    return kol;
  }

  private String createBd(String admin, String username) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "username",
                username,
                "password",
                PASSWORD,
                "nickname",
                username,
                "role",
                "STAFF",
                "postTemplate",
                "BD",
                "dataScope",
                "OWN"));
    return json.readTree(
            mvc.perform(
                    post("/admin-api/system/users")
                        .header(TOKEN, admin)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .at("/data/id")
        .asText();
  }

  private String companyAdmin() throws Exception {
    return CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  private String login(String username, String password) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/admin-api/auth/login")
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                Captchas.loginBody(mvc, json, username, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .at("/data/token")
        .asText();
  }

  @Test
  void shouldFilterByDisplayStatus_includingPendingUpdate() throws Exception {
    String admin = companyAdmin();
    String bd1 = createBd(admin, "bd-filter");
    String withBd =
        json.readTree(
                mvc.perform(
                        post("/admin-api/kol/kols")
                            .header(TOKEN, admin)
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"Bound\",\"email\":\"bound-filter@example.com\","
                                    + "\"country\":\"us\",\"bdUserId\":\""
                                    + bd1
                                    + "\",\"defaultRate\":\"0.10\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/id")
            .asText();
    String unbound =
        json.readTree(
                mvc.perform(
                        post("/admin-api/kol/kols")
                            .header(TOKEN, admin)
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"Free\",\"email\":\"free-filter@example.com\","
                                    + "\"country\":\"us\",\"defaultRate\":\"0.10\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/id")
            .asText();
    // New KOLs start PENDING_REVIEW; unbound shows as PENDING_UPDATE.
    mvc.perform(
            get("/admin-api/kol/kols")
                .param("displayStatus", "PENDING_UPDATE")
                .param("keyword", "free-filter@example.com")
                .header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(unbound));
    mvc.perform(
            get("/admin-api/kol/kols")
                .param("displayStatus", "PENDING_REVIEW")
                .param("keyword", "bound-filter@example.com")
                .header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(withBd));
    mvc.perform(
            get("/admin-api/kol/kols")
                .param("displayStatus", "PENDING_REVIEW")
                .param("keyword", "free-filter@example.com")
                .header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
  }

  @Test
  void shouldPreviewIdleCleanup_onlyWhenAllScopeAndEnabledPath() throws Exception {
    String admin = companyAdmin();
    mvc.perform(get("/admin-api/kol/cleanup-preview").header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    createBd(admin, "bd-cleanup-own");
    String bdToken = login("bd-cleanup-own", PASSWORD);
    mvc.perform(get("/admin-api/kol/cleanup-preview").header(TOKEN, bdToken))
        .andExpect(status().isForbidden());
  }
}
