package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * Task 05 against real PostgreSQL and Redis: migrations, mapping replace, concurrent exclusive
 * occupancy, partial mapping import, and task-file retention expiry.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProductIT {
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
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  @Autowired
  ProductIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  private String adminToken;

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_kol_item");
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    MvcResult result =
        mvc.perform(
                post("/admin-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("username", "bootstrap", "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    adminToken =
        json.readTree(result.getResponse().getContentAsString())
            .path("data")
            .path("token")
            .asText();
  }

  @Test
  void shouldApplyMigration_whenStarting() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'crm_shop'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'crm_product'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'crm_kol_item'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldReplaceExclusiveItem_keepingSingleCurrentMapping() throws Exception {
    long shopId = createShop("店A", "1001");
    String kolCode = createKol("Alice", "alice@example.com");
    String productCode = importProduct(shopId, "2001", "鞋");
    Long kolId =
        jdbc.queryForObject("SELECT id FROM crm_kol WHERE kol_code = ?", Long.class, kolCode);
    Long productId =
        jdbc.queryForObject(
            "SELECT id FROM crm_product WHERE product_code = ?", Long.class, productCode);

    importMapping("1001", "9001", kolCode, productCode);
    importMapping("1001", "9002", kolCode, productCode);

    Integer current =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_kol_item WHERE kol_id = ? AND product_id = ? AND valid_to IS NULL",
            Integer.class,
            kolId,
            productId);
    assertThat(current).isEqualTo(1);
    String activeItem =
        jdbc.queryForObject(
            "SELECT weidian_item_id FROM crm_kol_item WHERE kol_id = ? AND product_id = ? AND valid_to IS NULL",
            String.class,
            kolId,
            productId);
    assertThat(activeItem).isEqualTo("9002");
    Instant closed =
        jdbc.queryForObject(
            "SELECT valid_to FROM crm_kol_item WHERE weidian_item_id = '9001'", Instant.class);
    assertThat(closed).isNotNull();
  }

  @Test
  void shouldAllowOnlyOneWinner_whenTwoTransactionsClaimSameExclusiveItem() throws Exception {
    long shopId = createShop("店B", "1002");
    String kol1 = createKol("Bob", "bob@example.com");
    String kol2 = createKol("Carol", "carol@example.com");
    String productCode = importProduct(shopId, "2002", "包");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> one = () -> tryImport("1002", "9100", kol1, productCode);
      Callable<Boolean> two = () -> tryImport("1002", "9100", kol2, productCode);
      Future<Boolean> a = pool.submit(one);
      Future<Boolean> b = pool.submit(two);
      boolean first = a.get();
      boolean second = b.get();
      assertThat(first || second).isTrue();
      assertThat(first && second).isFalse();
      Integer current =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM crm_kol_item WHERE shop_id = ? AND weidian_item_id = '9100' AND valid_to IS NULL",
              Integer.class,
              shopId);
      assertThat(current).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldProducePartialErrorFile_whenMappingImportHasBadRows() throws Exception {
    long shopId = createShop("店C", "1003");
    String kolCode = createKol("Dave", "dave@example.com");
    String productCode = importProduct(shopId, "2003", "帽");
    byte[] workbook =
        mappingWorkbook(
            List.of(
                List.of("1003", "9201", kolCode, productCode),
                List.of("1003", "9201", kolCode, productCode), // duplicate exclusive in file
                List.of("1003", "9999", "KOL9999", productCode))); // missing kol
    MvcResult submit =
        mvc.perform(
                multipart("/admin-api/product/kol-item-imports")
                    .file(
                        new MockMultipartFile(
                            "file", "m.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    String taskId =
        json.readTree(submit.getResponse().getContentAsString())
            .path("data")
            .path("taskId")
            .asText();
    awaitTask(taskId);
    mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PARTIAL"))
        .andExpect(jsonPath("$.data.hasErrorFile").value(true));
  }

  @Test
  void shouldReturnTaskFileExpired_afterRetentionCleanup() throws Exception {
    long shopId = createShop("店D", "1004");
    String kolCode = createKol("Eve", "eve@example.com");
    String productCode = importProduct(shopId, "2004", "袜");
    byte[] workbook = mappingWorkbook(List.of(List.of("1004", "9301", "KOL9999", productCode)));
    MvcResult submit =
        mvc.perform(
                multipart("/admin-api/product/kol-item-imports")
                    .file(
                        new MockMultipartFile(
                            "file", "m.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    String taskId =
        json.readTree(submit.getResponse().getContentAsString())
            .path("data")
            .path("taskId")
            .asText();
    awaitTask(taskId);
    // Simulate 30-day retention cleanup: remove the ERROR file row (same as TaskFileRetentionJob).
    jdbc.update(
        "DELETE FROM crm_task_file WHERE task_id = ? AND kind = 'ERROR'", Long.valueOf(taskId));
    mvc.perform(get("/admin-api/task/tasks/" + taskId + "/error-file").header(TOKEN, adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_EXPIRED"));
  }

  private long createShop(String name, String weidianId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/product/shops")
                    .header(TOKEN, adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"shopName\":\"" + name + "\",\"weidianShopId\":\"" + weidianId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
  }

  private String createKol(String name, String email) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(TOKEN, adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"email\":\""
                            + email
                            + "\",\"country\":\"US\",\"socialAccounts\":[]}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("kolCode")
        .asText();
  }

  private String importProduct(long shopId, String itemId, String name) throws Exception {
    String weidianShopId =
        jdbc.queryForObject(
            "SELECT weidian_shop_id FROM crm_shop WHERE id = ?", String.class, shopId);
    byte[] workbook =
        productWorkbook(List.of(List.of("", weidianShopId, itemId, name, "", "", "10.00", "")));
    MvcResult submit =
        mvc.perform(
                multipart("/admin-api/product/product-imports")
                    .file(
                        new MockMultipartFile(
                            "file", "p.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    String taskId =
        json.readTree(submit.getResponse().getContentAsString())
            .path("data")
            .path("taskId")
            .asText();
    awaitTask(taskId);
    return jdbc.queryForObject(
        "SELECT product_code FROM crm_product WHERE weidian_item_id = ?", String.class, itemId);
  }

  private void importMapping(String shop, String item, String kol, String product)
      throws Exception {
    assertThat(tryImport(shop, item, kol, product)).isTrue();
  }

  private boolean tryImport(String shop, String item, String kol, String product) throws Exception {
    byte[] workbook = mappingWorkbook(List.of(List.of(shop, item, kol, product)));
    MvcResult submit =
        mvc.perform(
                multipart("/admin-api/product/kol-item-imports")
                    .file(
                        new MockMultipartFile(
                            "file", "m.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    String taskId =
        json.readTree(submit.getResponse().getContentAsString())
            .path("data")
            .path("taskId")
            .asText();
    JsonNode task = awaitTask(taskId);
    return "SUCCESS".equals(task.path("status").asText());
  }

  private JsonNode awaitTask(String taskId) throws Exception {
    for (int i = 0; i < 100; i++) {
      MvcResult result =
          mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, adminToken))
              .andExpect(status().isOk())
              .andReturn();
      JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
      String status = data.path("status").asText();
      if ("SUCCESS".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)) {
        return data;
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("task " + taskId + " did not finish");
  }

  private static byte[] productWorkbook(List<List<String>> rows) {
    return workbook(ProductImportColumn.headers(), rows);
  }

  private static byte[] mappingWorkbook(List<List<String>> rows) {
    return workbook(MappingImportColumn.headers(), rows);
  }

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(List::of).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }
}
