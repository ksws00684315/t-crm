package com.tcrm.admin.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickLogDO;
import com.tcrm.admin.link.service.ClickConsumer;
import com.tcrm.admin.link.service.ClickEvent;
import com.tcrm.admin.link.service.ClickMessage;
import com.tcrm.admin.link.service.ClickPartitionJob;
import com.tcrm.admin.link.service.ClickPartitions;
import com.tcrm.admin.link.service.ClickStream;
import com.tcrm.admin.link.service.LinkIndexService;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Task 06 against real PostgreSQL and Redis: link index written from mappings and status changes,
 * full rebuild repair, click stream consumption into the partitioned click log, partition upkeep.
 * The background consumer thread is disabled so the test drives {@link ClickConsumer} directly.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class LinkIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

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
    r.add("crm.link.click-consumer.enabled", () -> "false");
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final LinkIndexService index;
  private final ClickStream clickStream;
  private final ClickConsumer consumer;
  private final ClickPartitionJob partitions;
  private final ClickLogMapper clickLogs;

  @Autowired
  LinkIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      LinkIndexService index,
      ClickStream clickStream,
      ClickConsumer consumer,
      ClickPartitionJob partitions,
      ClickLogMapper clickLogs) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.index = index;
    this.clickStream = clickStream;
    this.consumer = consumer;
    this.partitions = partitions;
    this.clickLogs = clickLogs;
  }

  private String adminToken;

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_click_log");
    jdbc.update("DELETE FROM crm_kol_item");
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    Set<String> keys = redis.keys("link:*");
    keys.addAll(redis.keys("click:last:*"));
    keys.add("stream:click");
    redis.delete(keys);
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
  void shouldWriteIndexInRedirectFormat_andFollowStatusChanges() throws Exception {
    long shopId = createShop("店A", "1001");
    String kolCode = createKol("Alice", "alice@example.com");
    String productCode = importProduct(shopId, "2001", "鞋");
    importMapping("1001", "9001", kolCode, productCode);
    long kolItemId = currentKolItemId(kolCode, productCode);

    // Same format the redirect service's LinkEntry parses.
    assertThat(field(kolCode, productCode))
        .isEqualTo(
            "{\"kolItemId\":\""
                + kolItemId
                + "\",\"targetUrl\":\"https://weidian.com/item.html?itemID=9001\",\"status\":1}");

    long kolId = id("SELECT id FROM crm_kol WHERE kol_code = ?", kolCode);
    long productId = id("SELECT id FROM crm_product WHERE product_code = ?", productCode);
    changeStatus("/admin-api/kol/kols/" + kolId + "/status", 0);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/kol/kols/" + kolId + "/status", 1);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");
    changeStatus("/admin-api/product/products/" + productId + "/status", 0);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/product/products/" + productId + "/status", 1);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");
    changeStatus("/admin-api/product/shops/" + shopId + "/status", 0);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/product/shops/" + shopId + "/status", 1);
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");

    importMapping("1001", "9002", kolCode, productCode);
    long replaced = currentKolItemId(kolCode, productCode);
    assertThat(replaced).isNotEqualTo(kolItemId);
    assertThat(redis.opsForHash().keys("link:" + kolCode)).containsExactly(productCode);
    assertThat(field(kolCode, productCode))
        .isEqualTo(
            "{\"kolItemId\":\""
                + replaced
                + "\",\"targetUrl\":\"https://weidian.com/item.html?itemID=9002\",\"status\":1}");

    mvc.perform(get("/admin-api/link/links").param("kolCode", kolCode).header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(
            jsonPath("$.data.items[0].linkUrl")
                .value("https://t.example.com/t/" + kolCode + "/" + productCode))
        .andExpect(jsonPath("$.data.items[0].available").value(true))
        .andExpect(jsonPath("$.data.items[0].clicks30d").value(0));
  }

  @Test
  void shouldRepairTamperedIndex_whenRebuilt() throws Exception {
    long shopId = createShop("店B", "1002");
    String kolCode = createKol("Bob", "bob@example.com");
    String p1 = importProduct(shopId, "2101", "包");
    String p2 = importProduct(shopId, "2102", "帽");
    importMapping("1002", "9101", kolCode, p1);
    importMapping("1002", "9102", kolCode, p2);
    String good1 = field(kolCode, p1);
    String good2 = field(kolCode, p2);

    redis.opsForHash().delete("link:" + kolCode, p1);
    redis.opsForHash().put("link:" + kolCode, p2, "{\"kolItemId\":\"1\"}");
    redis.opsForHash().put("link:" + kolCode, "P999999", "stale");
    redis.opsForHash().put("link:KOL99999", "P1", "orphan");

    index.rebuildAll();

    assertThat(field(kolCode, p1)).isEqualTo(good1);
    assertThat(field(kolCode, p2)).isEqualTo(good2);
    assertThat(redis.opsForHash().keys("link:" + kolCode)).containsExactlyInAnyOrder(p1, p2);
    assertThat(redis.hasKey("link:KOL99999")).isFalse();
    assertThat(redis.keys("link-rebuild:*")).isEmpty();

    mvc.perform(post("/admin-api/link/index-rebuilds").header(TOKEN, adminToken))
        .andExpect(status().isAccepted());
  }

  @Test
  void shouldStoreClicksOnce_withBotAndDupFlags() throws Exception {
    long shopId = createShop("店C", "1003");
    String kolCode = createKol("Carol", "carol@example.com");
    String productCode = importProduct(shopId, "2201", "袜");
    importMapping("1003", "9201", kolCode, productCode);
    long kolItemId = currentKolItemId(kolCode, productCode);
    long kolId = id("SELECT id FROM crm_kol WHERE kol_code = ?", kolCode);
    long productId = id("SELECT id FROM crm_product WHERE product_code = ?", productCode);
    clickStream.ensureGroup();
    long t0 = System.currentTimeMillis() - 60_000;

    String first = append(kolItemId, t0, HASH_A, "Mozilla/5.0");
    append(kolItemId, t0 + 9_999, HASH_A, "Mozilla/5.0"); // dup of the first
    append(kolItemId, t0 + 19_999, HASH_A, "Mozilla/5.0"); // 10,000 ms after: not dup
    append(kolItemId, t0, HASH_B, "facebookexternalhit/1.1"); // other IP: bot only
    append(999_999_999L, t0, HASH_A, "Mozilla/5.0"); // unknown mapping: skipped
    redis.opsForStream().add(MapRecord.create("stream:click", Map.of("kolItemId", "x")));

    assertThat(consumer.pollOnce()).isEqualTo(6);
    assertThat(consumer.pollOnce()).isZero();

    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM crm_click_log ORDER BY click_time, ip_hash, id");
    assertThat(rows).hasSize(4);
    assertThat(rows).extracting(r -> r.get("is_bot")).containsExactly(false, true, false, false);
    assertThat(rows).extracting(r -> r.get("is_dup")).containsExactly(false, false, true, false);
    assertThat(rows).allSatisfy(r -> assertThat(r.get("kol_id")).isEqualTo(kolId));
    assertThat(rows).allSatisfy(r -> assertThat(r.get("product_id")).isEqualTo(productId));
    String[] idParts = first.split("-");
    assertThat(rows.get(0).get("id"))
        .isEqualTo(Long.parseLong(idParts[0]) * 1_000_000 + Long.parseLong(idParts[1]));
    assertThat(pending()).isZero();

    // Redelivery: an entry delivered but not acknowledged (crash before XACK) comes back from the
    // pending list; replaying an already stored batch adds no rows.
    append(kolItemId, t0 + 40_000, HASH_B, "Mozilla/5.0");
    var delivered = clickStream.readNew(500, Duration.ofMillis(100));
    assertThat(delivered).hasSize(1);
    assertThat(pending()).isEqualTo(1);
    assertThat(consumer.pollOnce()).isEqualTo(1);
    assertThat(pending()).isZero();
    assertThat(count()).isEqualTo(5);
    replayStored(delivered);
    assertThat(count()).isEqualTo(5);

    mvc.perform(get("/admin-api/link/links").param("kolCode", kolCode).header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].clicks30d").value(5))
        .andExpect(jsonPath("$.data.items[0].validClicks30d").value(3));
  }

  @Test
  void shouldKeepLinkClickCounts_whenExclusiveItemChanges() throws Exception {
    long shopId = createShop("店D", "1004");
    String kolCode = createKol("Dave", "dave@example.com");
    String otherKol = createKol("Erin", "erin@example.com");
    String productCode = importProduct(shopId, "2301", "裙");
    String otherProduct = importProduct(shopId, "2302", "衫");
    importMapping("1004", "9301", kolCode, productCode);
    importMapping("1004", "9302", kolCode, otherProduct);
    importMapping("1004", "9303", otherKol, productCode);
    long before = currentKolItemId(kolCode, productCode);
    clickStream.ensureGroup();
    long t0 = System.currentTimeMillis() - 60_000;
    append(before, t0, HASH_A, "Mozilla/5.0");
    append(before, t0 + 1_000, HASH_A, "Mozilla/5.0"); // dup
    append(before, t0 + 2_000, HASH_A, "Mozilla/5.0"); // dup
    append(currentKolItemId(kolCode, otherProduct), t0, HASH_A, "Mozilla/5.0");
    append(currentKolItemId(otherKol, productCode), t0, HASH_A, "Mozilla/5.0");
    assertThat(consumer.pollOnce()).isEqualTo(5);
    assertLinkCounts(kolCode, productCode, "9301", 3, 1);

    // Changing the exclusive item creates a new crm_kol_item row; the link (KOL + original
    // product) and its clicks stay the same.
    importMapping("1004", "8888888", kolCode, productCode);
    long after = currentKolItemId(kolCode, productCode);
    assertThat(after).isNotEqualTo(before);
    assertLinkCounts(kolCode, productCode, "8888888", 3, 1);

    append(after, t0 + 30_000, HASH_B, "Mozilla/5.0");
    append(before, t0 + 40_000, HASH_A, "Mozilla/5.0"); // in flight with the closed mapping ID
    assertThat(consumer.pollOnce()).isEqualTo(2);
    assertLinkCounts(kolCode, productCode, "8888888", 5, 3);
    assertThat(
            jdbc.queryForList(
                "SELECT kol_item_id FROM crm_click_log WHERE click_time >= ? ORDER BY click_time",
                Long.class,
                Timestamp.from(Instant.ofEpochMilli(t0 + 30_000))))
        .containsExactly(after, before);
    assertLinkCounts(kolCode, otherProduct, "9302", 1, 1);
    assertLinkCounts(otherKol, productCode, "9303", 1, 1);
  }

  private void assertLinkCounts(
      String kolCode, String productCode, String itemId, long total, long valid) throws Exception {
    mvc.perform(
            get("/admin-api/link/links")
                .param("kolCode", kolCode)
                .param("productCode", productCode)
                .header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(
            jsonPath("$.data.items[0].linkUrl")
                .value("https://t.example.com/t/" + kolCode + "/" + productCode))
        .andExpect(
            jsonPath("$.data.items[0].targetUrl")
                .value("https://weidian.com/item.html?itemID=" + itemId))
        .andExpect(jsonPath("$.data.items[0].clicks30d").value(total))
        .andExpect(jsonPath("$.data.items[0].validClicks30d").value(valid));
  }

  @Test
  void shouldCreateFuturePartitions_andDropExpiredOnes() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    YearMonth old = YearMonth.from(today).minusMonths(14);
    String oldName = ClickPartitions.name(old);
    clickLogs.createPartition(oldName, ClickPartitions.from(old), ClickPartitions.to(old));

    partitions.maintain(today);

    List<String> names = clickLogs.listPartitions();
    for (YearMonth month : ClickPartitions.required(today)) {
      assertThat(names).contains(ClickPartitions.name(month));
    }
    assertThat(names).doesNotContain(oldName);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_class WHERE relname = ?", Integer.class, oldName))
        .isZero();
    partitions.maintain(today); // idempotent
  }

  private void replayStored(List<ClickMessage> messages) {
    var rows = new ArrayList<ClickLogDO>();
    for (var m : messages) {
      var event = ClickEvent.parse(m.id(), m.fields()).orElseThrow();
      var row = new ClickLogDO();
      row.setId(event.id());
      row.setClickTime(Instant.ofEpochMilli(event.clickMillis()));
      row.setKolId(1L);
      row.setProductId(1L);
      row.setKolItemId(event.kolItemId());
      row.setIpHash(event.ipHash());
      row.setUa(event.ua());
      row.setReferer(event.referer());
      row.setCountry(event.country());
      row.setBot(false);
      row.setDup(false);
      row.setCreateTime(Instant.now());
      rows.add(row);
    }
    assertThat(clickLogs.insertIgnore(rows)).isZero();
  }

  private String append(long kolItemId, long ts, String ipHash, String ua) {
    Map<String, String> event = new HashMap<>();
    event.put("kolItemId", Long.toString(kolItemId));
    event.put("kolCode", "KOL0001");
    event.put("productCode", "P1");
    event.put("ts", Long.toString(ts));
    event.put("ipHash", ipHash);
    event.put("ua", ua);
    event.put("ref", "");
    event.put("country", "US");
    return redis.opsForStream().add(MapRecord.create("stream:click", event)).getValue();
  }

  private long pending() {
    return redis.opsForStream().pending("stream:click", "crm-admin").getTotalPendingMessages();
  }

  private long count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM crm_click_log", Long.class);
  }

  private String field(String kolCode, String productCode) {
    return (String) redis.opsForHash().get("link:" + kolCode, productCode);
  }

  private long id(String sql, String arg) {
    return jdbc.queryForObject(sql, Long.class, arg);
  }

  private long currentKolItemId(String kolCode, String productCode) {
    return jdbc.queryForObject(
        "SELECT i.id FROM crm_kol_item i JOIN crm_kol k ON k.id = i.kol_id"
            + " JOIN crm_product p ON p.id = i.product_id"
            + " WHERE k.kol_code = ? AND p.product_code = ? AND i.valid_to IS NULL",
        Long.class,
        kolCode,
        productCode);
  }

  private void changeStatus(String path, int status) throws Exception {
    mvc.perform(
            put(path)
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":" + status + "}"))
        .andExpect(status().isOk());
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
        workbook(
            ProductImportColumn.headers(),
            List.of(List.of("", weidianShopId, itemId, name, "", "", "10.00", "")));
    awaitTask(submit("/admin-api/product/product-imports", workbook));
    return jdbc.queryForObject(
        "SELECT product_code FROM crm_product WHERE weidian_item_id = ?", String.class, itemId);
  }

  private void importMapping(String shop, String item, String kol, String product)
      throws Exception {
    byte[] workbook =
        workbook(MappingImportColumn.headers(), List.of(List.of(shop, item, kol, product)));
    JsonNode task = awaitTask(submit("/admin-api/product/kol-item-imports", workbook));
    assertThat(task.path("status").asText()).isEqualTo("SUCCESS");
  }

  private String submit(String path, byte[] workbook) throws Exception {
    MvcResult submit =
        mvc.perform(
                multipart(path)
                    .file(
                        new MockMultipartFile(
                            "file", "f.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, adminToken))
            .andExpect(status().isAccepted())
            .andReturn();
    return json.readTree(submit.getResponse().getContentAsString())
        .path("data")
        .path("taskId")
        .asText();
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

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(List::of).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }
}
