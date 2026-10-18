package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.service.OrderImportColumn;
import com.tcrm.admin.order.service.OrderPayloadDTO;
import com.tcrm.admin.order.service.OrderWriter;
import com.tcrm.admin.order.service.RefundImportColumn;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Task 18 order/refund Excel import against real PostgreSQL and Redis. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderImportIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final Instant PAID = Instant.parse("2026-10-16T04:01:00Z"); // 12:01 Beijing

  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer("postgres:17.11")
          .withDatabaseName("tcrm")
          .withUsername("tcrm")
          .withPassword(UUID.randomUUID().toString());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("crm.bootstrap.username", () -> "bootstrap");
    r.add("crm.bootstrap.password", () -> PASSWORD);
    r.add("crm.link.base-url", () -> "https://t.example.test");
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.test");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final OrderWriter writer;
  private String admin;

  @Autowired
  OrderImportIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, OrderWriter writer) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.writer = writer;
  }

  @BeforeEach
  void prepare() throws Exception {
    for (String table :
        List.of(
            "crm_refund",
            "crm_order_item",
            "crm_order",
            "crm_order_sync_state",
            "crm_kol_item",
            "crm_product",
            "crm_shop",
            "crm_kol_bd_history",
            "crm_kol",
            "crm_task_file",
            "crm_task")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_name,weidian_shop_id,status,create_time,update_time)"
            + " VALUES(10,1,'Shop A','100',1,now(),now())");
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_name,weidian_shop_id,status,create_time,update_time)"
            + " VALUES(20,2,'Other','200',1,now(),now())");
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,create_time,update_time)"
            + " VALUES(100,1,'KOL-AAA100','Creator','kol0100@example.test','US',now(),now())");
    jdbc.update(
        "INSERT INTO crm_product(id,tenant_id,product_code,shop_id,weidian_item_id,name,price,create_time,update_time)"
            + " VALUES(1,1,'P1',10,'8001','Original',10,now(),now())");
    jdbc.update(
        "INSERT INTO crm_kol_item(id,tenant_id,kol_id,product_id,shop_id,weidian_item_id,valid_from,valid_to,create_time,update_time)"
            + " VALUES(1,1,100,1,10,'9001',?,?,now(),now())",
        Timestamp.from(PAID.minusSeconds(3600)),
        null);
  }

  @Test
  void shouldImportMultiLineOrderIdempotently_andShowInListAndUnmatchedPool() throws Exception {
    List<List<String>> rows =
        List.of(
            List.of(
                "100",
                "7001",
                "",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "20.00",
                "0",
                "9001",
                "s1",
                "Item A",
                "Red",
                "1",
                "10.00"),
            List.of(
                "100",
                "7001",
                "",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "20.00",
                "0",
                "9002",
                "s2",
                "Item B",
                "Blue",
                "1",
                "10.00"),
            List.of(
                "100",
                "7002",
                "sub-x",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "5.00",
                "0",
                "9002",
                "",
                "Unmatched item",
                "",
                "1",
                "5.00"));
    String taskId = submit("/admin-api/order/order-imports", orderWorkbook(rows), "o.xlsx");
    JsonNode task = awaitTask(taskId);
    assertThat(task.path("status").asText()).isEqualTo("SUCCESS");
    assertThat(task.path("failed").asInt()).isZero();

    Map<String, Object> order =
        jdbc.queryForMap("SELECT * FROM crm_order WHERE weidian_order_id='7001'");
    assertThat(order.get("source")).isEqualTo(2);
    assertThat(((Number) order.get("import_batch_id")).longValue())
        .isEqualTo(Long.parseLong(taskId));
    assertThat(order.get("attribution_status")).isEqualTo("UNMATCHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=?",
                Long.class,
                order.get("id")))
        .isEqualTo(2L);

    // Re-import the same file: still two lines, no duplicates.
    String again = submit("/admin-api/order/order-imports", orderWorkbook(rows), "o2.xlsx");
    assertThat(awaitTask(again).path("status").asText()).isEqualTo("SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=?",
                Long.class,
                order.get("id")))
        .isEqualTo(2L);
    Map<String, Object> after =
        jdbc.queryForMap(
            "SELECT source, import_batch_id FROM crm_order WHERE weidian_order_id='7001'");
    assertThat(after.get("source")).isEqualTo(2);
    assertThat(((Number) after.get("import_batch_id")).longValue())
        .isEqualTo(Long.parseLong(again));

    mvc.perform(get("/admin-api/order/orders").header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("2"));
    mvc.perform(get("/admin-api/order/unmatched").header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("2"));
  }

  @Test
  void shouldRejectSyncOwnedOrder_andImportRefund() throws Exception {
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,order_status,raw_status,"
            + "order_time,pay_time,total_amount,pay_amount,freight,discount,source,attribution_status,"
            + "last_sync_time,create_time,update_time) VALUES(50,1,10,'8001','PAID','pay',now(),now(),10,10,0,0,1,"
            + "'PENDING',now(),now(),now())");
    jdbc.update(
        "INSERT INTO crm_order_item(id,tenant_id,order_id,sub_order_id,weidian_item_id,sku_id,"
            + "item_name,sku_name,quantity,pay_amount,refund_amount,attribution_type,create_time,update_time)"
            + " VALUES(51,1,50,'sub-1','9001','','Line','',1,10,0,0,now(),now())");

    List<List<String>> syncOwned =
        List.of(
            List.of(
                "100",
                "8001",
                "sub-1",
                "PAID",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "10.00",
                "0",
                "9001",
                "",
                "Line",
                "",
                "1",
                "10.00"));
    String blocked =
        submit("/admin-api/order/order-imports", orderWorkbook(syncOwned), "blocked.xlsx");
    JsonNode blockedTask = awaitTask(blocked);
    assertThat(blockedTask.path("status").asText()).isEqualTo("FAILED");
    assertThat(blockedTask.path("failed").asInt()).isEqualTo(1);

    List<List<String>> refundRows =
        List.of(
            List.of(
                "100",
                "RF-1",
                "8001",
                "sub-1",
                "3.50",
                "2026-10-16 15:00:00",
                "2026-10-16 16:00:00"));
    String refundTask =
        submit("/admin-api/order/refund-imports", refundWorkbook(refundRows), "r.xlsx");
    assertThat(awaitTask(refundTask).path("status").asText()).isEqualTo("SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT raw_status FROM crm_refund WHERE weidian_refund_no='RF-1'", String.class))
        .isEqualTo("10");
    assertThat(
            jdbc.queryForObject(
                "SELECT refund_amount FROM crm_order_item WHERE id=51", java.math.BigDecimal.class))
        .isEqualByComparingTo("3.50");

    // Duplicate refund number fails.
    String dup = submit("/admin-api/order/refund-imports", refundWorkbook(refundRows), "r2.xlsx");
    assertThat(awaitTask(dup).path("failed").asInt()).isEqualTo(1);
  }

  @Test
  void shouldReplaceImportLines_whenApiSyncTakesOverSameOrder() throws Exception {
    List<List<String>> rows =
        List.of(
            List.of(
                "100",
                "7100",
                "",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "20.00",
                "0",
                "9001",
                "s1",
                "Item A",
                "Red",
                "1",
                "10.00"),
            List.of(
                "100",
                "7100",
                "",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "20.00",
                "0",
                "9002",
                "s2",
                "Item B",
                "Blue",
                "1",
                "10.00"));
    String taskId = submit("/admin-api/order/order-imports", orderWorkbook(rows), "imp.xlsx");
    assertThat(awaitTask(taskId).path("status").asText()).isEqualTo("SUCCESS");
    Map<String, Object> order =
        jdbc.queryForMap("SELECT * FROM crm_order WHERE weidian_order_id='7100'");
    assertThat(order.get("source")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=? AND is_deleted = FALSE",
                Long.class,
                order.get("id")))
        .isEqualTo(2L);
    List<String> importSubs =
        jdbc.query(
            "SELECT sub_order_id FROM crm_order_item WHERE order_id=? AND is_deleted = FALSE ORDER BY id",
            (rs, i) -> rs.getString(1),
            order.get("id"));
    assertThat(importSubs).allSatisfy(s -> assertThat(s).startsWith("line-"));

    OrderDO api = new OrderDO();
    api.setShopId(10L);
    api.setWeidianOrderId("7100");
    api.setOrderStatus("PAID");
    api.setRawStatus("pay");
    api.setOrderTime(PAID.minusSeconds(60));
    api.setPayTime(PAID);
    api.setTotalAmount(new BigDecimal("20.00"));
    api.setPayAmount(new BigDecimal("20.00"));
    api.setFreight(BigDecimal.ZERO.setScale(2));
    api.setDiscount(BigDecimal.ZERO.setScale(2));
    api.setSource(1);
    api.setSourceUpdateTime(Instant.now());
    api.setLastSyncTime(Instant.now());
    api.setAttributionStatus("PENDING");
    OrderItemDO a = new OrderItemDO();
    a.setSubOrderId("wd-sub-a");
    a.setWeidianItemId("9001");
    a.setSkuId("s1");
    a.setItemName("Item A");
    a.setSkuName("Red");
    a.setQuantity(1);
    a.setPayAmount(new BigDecimal("10.00"));
    a.setRefundAmount(BigDecimal.ZERO.setScale(2));
    a.setAttributionType(0);
    OrderItemDO b = new OrderItemDO();
    b.setSubOrderId("wd-sub-b");
    b.setWeidianItemId("9002");
    b.setSkuId("s2");
    b.setItemName("Item B");
    b.setSkuName("Blue");
    b.setQuantity(1);
    b.setPayAmount(new BigDecimal("10.00"));
    b.setRefundAmount(BigDecimal.ZERO.setScale(2));
    b.setAttributionType(0);
    TenantScope.runCompany(1, () -> writer.save(new OrderPayloadDTO(api, List.of(a, b))));

    Map<String, Object> after =
        jdbc.queryForMap("SELECT source, pay_amount FROM crm_order WHERE weidian_order_id='7100'");
    assertThat(after.get("source")).isEqualTo(1);
    assertThat((java.math.BigDecimal) after.get("pay_amount")).isEqualByComparingTo("20.00");
    List<String> apiSubs =
        jdbc.query(
            "SELECT sub_order_id FROM crm_order_item WHERE order_id=? AND is_deleted = FALSE ORDER BY id",
            (rs, i) -> rs.getString(1),
            order.get("id"));
    assertThat(apiSubs).containsExactly("wd-sub-a", "wd-sub-b");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=? AND is_deleted = TRUE AND sub_order_id LIKE 'line-%'",
                Long.class, order.get("id")))
        .isEqualTo(2L);
  }

  @Test
  void shouldAllowReinsertingSoftDeletedIdentity_whenPartialUniqueIndexApplies() throws Exception {
    List<List<String>> rows =
        List.of(
            List.of(
                "100",
                "7200",
                "",
                "已付款",
                "2026-10-16 12:00:00",
                "2026-10-16 12:01:00",
                "10.00",
                "0",
                "9001",
                "s1",
                "Item A",
                "Red",
                "1",
                "10.00"));
    String taskId = submit("/admin-api/order/order-imports", orderWorkbook(rows), "reins.xlsx");
    assertThat(awaitTask(taskId).path("status").asText()).isEqualTo("SUCCESS");
    Map<String, Object> order =
        jdbc.queryForMap("SELECT * FROM crm_order WHERE weidian_order_id='7200'");
    String softKey =
        jdbc.queryForObject(
            "SELECT sub_order_id FROM crm_order_item WHERE order_id=? AND is_deleted = FALSE",
            String.class,
            order.get("id"));
    assertThat(softKey).startsWith("line-");

    OrderDO api = new OrderDO();
    api.setShopId(10L);
    api.setWeidianOrderId("7200");
    api.setOrderStatus("PAID");
    api.setRawStatus("pay");
    api.setOrderTime(PAID.minusSeconds(60));
    api.setPayTime(PAID);
    api.setTotalAmount(new BigDecimal("10.00"));
    api.setPayAmount(new BigDecimal("10.00"));
    api.setFreight(BigDecimal.ZERO.setScale(2));
    api.setDiscount(BigDecimal.ZERO.setScale(2));
    api.setSource(1);
    api.setSourceUpdateTime(Instant.now());
    api.setLastSyncTime(Instant.now());
    api.setAttributionStatus("PENDING");
    OrderItemDO a = new OrderItemDO();
    a.setSubOrderId("wd-sub-7200");
    a.setWeidianItemId("9001");
    a.setSkuId("s1");
    a.setItemName("Item A");
    a.setSkuName("Red");
    a.setQuantity(1);
    a.setPayAmount(new BigDecimal("10.00"));
    a.setRefundAmount(BigDecimal.ZERO.setScale(2));
    a.setAttributionType(0);
    TenantScope.runCompany(1, () -> writer.save(new OrderPayloadDTO(api, List.of(a))));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=? AND is_deleted = TRUE AND sub_order_id=?",
                Long.class,
                order.get("id"),
                softKey))
        .isEqualTo(1L);

    // Second API sync carries the soft-deleted import key again; live unique index must allow it.
    OrderItemDO again = new OrderItemDO();
    again.setSubOrderId(softKey);
    again.setWeidianItemId("9001");
    again.setSkuId("s1");
    again.setItemName("Item A");
    again.setSkuName("Red");
    again.setQuantity(1);
    again.setPayAmount(new BigDecimal("10.00"));
    again.setRefundAmount(BigDecimal.ZERO.setScale(2));
    again.setAttributionType(0);
    api.setSourceUpdateTime(Instant.now().plusSeconds(1));
    TenantScope.runCompany(1, () -> writer.save(new OrderPayloadDTO(api, List.of(again))));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item WHERE order_id=? AND is_deleted = FALSE AND sub_order_id=?",
                Long.class,
                order.get("id"),
                softKey))
        .isEqualTo(1L);
  }

  void shouldForbidImportWithoutPermission_andHideOtherCompanyShop() throws Exception {
    mvc.perform(
            multipart("/admin-api/order/order-imports")
                .file(
                    new MockMultipartFile(
                        "file",
                        "x.xlsx",
                        ImportTaskApi.XLSX_CONTENT_TYPE,
                        orderWorkbook(List.of())))
                .header(TOKEN, CompanySessions.login(mvc, json, "bootstrap", PASSWORD)))
        .andExpect(status().isForbidden());

    List<List<String>> otherShop =
        List.of(
            List.of(
                "200",
                "9001",
                "",
                "UNPAID",
                "2026-10-16 12:00:00",
                "",
                "1.00",
                "0",
                "1",
                "",
                "X",
                "",
                "1",
                "1.00"));
    String taskId = submit("/admin-api/order/order-imports", orderWorkbook(otherShop), "t.xlsx");
    JsonNode task = awaitTask(taskId);
    assertThat(task.path("failed").asInt()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_order", Long.class)).isZero();
  }

  private String submit(String path, byte[] workbook, String name) throws Exception {
    MvcResult result =
        mvc.perform(
                multipart(path)
                    .file(
                        new MockMultipartFile(
                            "file", name, ImportTaskApi.XLSX_CONTENT_TYPE, workbook))
                    .header(TOKEN, admin)
                    .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isAccepted())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).at("/data/taskId").asText();
  }

  private JsonNode awaitTask(String taskId) throws Exception {
    JsonNode[] holder = new JsonNode[1];
    TestWait.until(
        java.time.Duration.ofSeconds(30),
        java.time.Duration.ofMillis(100),
        () -> {
          try {
            MvcResult result =
                mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, admin))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
            String status = data.path("status").asText();
            if ("SUCCESS".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)) {
              holder[0] = data;
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

  private static byte[] orderWorkbook(List<List<String>> rows) {
    return workbook(com.tcrm.admin.TestTemplates.render(OrderImportColumn.headerKeys()), rows);
  }

  private static byte[] refundWorkbook(List<List<String>> rows) {
    return workbook(com.tcrm.admin.TestTemplates.render(RefundImportColumn.headerKeys()), rows);
  }

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(List::of).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }
}
