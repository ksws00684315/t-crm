package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.order.service.OrderNormalizer;
import com.tcrm.admin.order.service.OrderSync;
import com.tcrm.admin.order.service.OrderWriter;
import com.tcrm.admin.shared.TenantScope;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 40 query surfaces: order list filters and refund status, detail, chain, refund queries, sync
 * monitoring with failures and import cutoff, and manual-match evidence.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderQueryIT {
  static final String PASSWORD = UUID.randomUUID().toString();
  static final Instant PAID = Instant.parse("2026-10-16T00:00:00Z");

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

  @MockitoBean WeidianBusinessApi remote;
  private final JdbcTemplate jdbc;
  private final MockMvc mvc;
  private final ObjectMapper json;
  private final OrderWriter writer;
  private final OrderNormalizer normalizer;
  private final OrderSync sync;
  private final StringRedisTemplate redis;

  @Autowired
  OrderQueryIT(
      JdbcTemplate jdbc,
      MockMvc mvc,
      ObjectMapper json,
      OrderWriter writer,
      OrderNormalizer normalizer,
      OrderSync sync,
      StringRedisTemplate redis,
      org.redisson.api.RedissonClient redisson) {
    this.jdbc = jdbc;
    this.mvc = mvc;
    this.json = json;
    this.writer = writer;
    this.normalizer = normalizer;
    this.sync = sync;
    this.redis = redis;
  }

  private String admin;

  @BeforeEach
  void prepare() throws Exception {
    reset(remote);
    redis.delete("order:bd-snapshot:checkpoint");
    for (String table :
        List.of(
            "crm_order_sync_failure",
            "crm_refund",
            "crm_order_item",
            "crm_order",
            "crm_order_sync_state",
            "crm_copy_job_item",
            "crm_copy_job",
            "crm_kol_item",
            "crm_weidian_shop_auth",
            "crm_product",
            "crm_shop",
            "crm_kol_bd_history",
            "crm_kol")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    shop(10);
    kol(100, "KOL-AAA100");
    kol(200, "KOL-AAA200");
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time) VALUES(10,1,10,'SERVICE','AUTHORIZED',now(),now())");
    when(remote.orderList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(remote.refundList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
  }

  private void shop(long id) {
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,create_time,update_time) VALUES(?,1,?,?,?,now(),now())",
        id,
        "SHOP-" + String.format("%06d", id),
        "Main shop",
        String.valueOf(id));
  }

  private void kol(long id, String code) {
    kol(id, code, "Creator " + code);
  }

  private void kol(long id, String code, String name) {
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,create_time,update_time) VALUES(?,1,?,?,?,'US',now(),now())",
        id,
        code,
        name,
        code.toLowerCase() + "@example.test");
  }

  /** Mapping row plus its original product; returns the kol_item id. */
  private long mapping(long id, long kol, String item, String image, Instant from, Instant to) {
    jdbc.update(
        "INSERT INTO crm_product(id,tenant_id,product_code,shop_id,weidian_item_id,name,image_url,price,create_time,update_time) VALUES(?,1,?,10,?,'Test original',?,10,now(),now())",
        id,
        "P" + id,
        "8" + id,
        image);
    jdbc.update(
        "INSERT INTO crm_kol_item(id,tenant_id,kol_id,product_id,shop_id,weidian_item_id,link_code,valid_from,valid_to,create_time,update_time) VALUES(?,1,?,?,10,?,?,?,?,now(),now())",
        id,
        kol,
        id,
        item,
        "KPL-" + String.format("%012X", id),
        Timestamp.from(from),
        to == null ? null : Timestamp.from(to));
    return id;
  }

  private WeidianBusinessApi.OrderLine line(String item, String sub) {
    return new WeidianBusinessApi.OrderLine(
        item,
        "Test item",
        BigDecimal.TEN,
        BigDecimal.TEN,
        BigDecimal.ONE,
        json.readTree("{\"sub_order_id\":\"" + sub + "\",\"sku_id\":\"0\"}"));
  }

  private long save(String id, Instant paid, WeidianBusinessApi.OrderLine... lines) {
    return saveAt(id, "pay", PAID.minusSeconds(100), paid, lines);
  }

  /** Task 70 B01 fixture: the platform order time is set independently of the pay time. */
  private long saveAt(
      String id,
      String status,
      Instant ordered,
      Instant paid,
      WeidianBusinessApi.OrderLine... lines) {
    var detail =
        new WeidianBusinessApi.OrderDetailResponse(
            id,
            status,
            "",
            BigDecimal.TEN,
            BigDecimal.TEN,
            ordered,
            paid,
            List.of(lines),
            json.readTree("{\"buyer_info\":{\"buyer_id\":\"b\"}}"));
    TenantScope.runCompany(
        1, () -> writer.save(normalizer.normalize(10, detail, paid == null ? ordered : paid)));
    return jdbc.queryForObject("SELECT id FROM crm_order WHERE weidian_order_id=?", Long.class, id);
  }

  private long refund(
      long id, long orderId, Long itemId, String no, String status, String type, String amount) {
    jdbc.update(
        "INSERT INTO crm_refund(id,tenant_id,order_id,order_item_id,weidian_refund_no,amount,raw_status,refund_type,refund_status,refund_time,finish_time,create_time,update_time) "
            + "VALUES(?,1,?,?,?,?,'10',?,?,?,?,now(),now())",
        id,
        orderId,
        itemId,
        no,
        new BigDecimal(amount),
        type,
        status,
        Timestamp.from(PAID.plusSeconds(60)),
        "SUCCESS".equals(status) ? Timestamp.from(PAID.plusSeconds(120)) : null);
    return id;
  }

  @Test
  void shouldFilterByOrderNumberAndChannel_whenListingOrders() throws Exception {
    long first = save("1000", PAID, line("90", "1"));
    save("1001", PAID, line("90", "1"));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderNo", "1000")
                .param("channel", "WEIDIAN")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(first)))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"))
        .andExpect(jsonPath("$.data.items[0].channel").value("WEIDIAN"))
        .andExpect(jsonPath("$.data.items[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data.items[0].shopCode").value("SHOP-000010"))
        .andExpect(jsonPath("$.data.items[0].refundStatus").value("NONE"))
        .andExpect(jsonPath("$.data.items[0].freight").value("0.00"));
    mvc.perform(
            get("/admin-api/order/orders").param("channel", "TMALL").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
  }

  @Test
  void shouldDeriveOrderRefundStatus_whenListingAndDetailingOrders() throws Exception {
    mapping(1, 100, "90", null, PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    long itemId = jdbc.queryForObject("SELECT id FROM crm_order_item", Long.class);
    refund(1, orderId, itemId, "R1", "SUCCESS", "RETURN_REFUND", "5.00");
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].refundStatus").value("PARTIAL_SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].refundAmount").value("5.00"));
    refund(2, orderId, null, "R2", "PROCESSING", "REFUND_ONLY", "5.00");
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].refundStatus").value("PROCESSING"));
    jdbc.update("DELETE FROM crm_refund WHERE id=2");
    jdbc.update("UPDATE crm_refund SET amount=10.00 WHERE id=1");
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].refundStatus").value("FULL_SUCCESS"));
  }

  @Test
  void shouldReturnOrderDetailWithMerchantAmountsAndUnitPrice() throws Exception {
    mapping(1, 100, "90", "https://img.example/p1.png", PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    mvc.perform(get("/admin-api/order/orders/" + orderId).header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderNo").value("1000"))
        .andExpect(jsonPath("$.data.shopCode").value("SHOP-000010"))
        .andExpect(jsonPath("$.data.merchantNo").value("10"))
        .andExpect(jsonPath("$.data.source").value("API"))
        .andExpect(jsonPath("$.data.itemAmount").value("10.00"))
        .andExpect(jsonPath("$.data.totalAmount").value("10.00"))
        .andExpect(jsonPath("$.data.payTime").value(PAID.toString()))
        .andExpect(jsonPath("$.data.items[0].imageUrl").value("https://img.example/p1.png"))
        .andExpect(jsonPath("$.data.items[0].unitPrice").value("10.00"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL-AAA100"))
        .andExpect(jsonPath("$.data.items[0].status").value("PAID"));
  }

  @Test
  void shouldChainCopyJobLinkAndRefunds_whenOrderLineIsAttributed() throws Exception {
    mapping(1, 100, "90", null, PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    long itemId = jdbc.queryForObject("SELECT id FROM crm_order_item", Long.class);
    jdbc.update(
        "INSERT INTO crm_copy_job(id,tenant_id,kol_id,token_id,job_code,caller_platform,target_shop_id,target_channel,status,total_count,success_count,fail_count,create_time,update_time) "
            + "VALUES(50,1,100,60,'job-abc','google_sheet_plugin',10,'WEIDIAN','completed',1,1,0,now(),now())");
    jdbc.update(
        "INSERT INTO crm_copy_job_item(id,job_id,tenant_id,source_channel,source_product_id,status,target_item_id,update_time) "
            + "VALUES(51,50,1,'WEIDIAN','8','success','90',now())");
    refund(1, orderId, itemId, "R1", "SUCCESS", "RETURN_REFUND", "5.00");

    mvc.perform(get("/admin-api/order/orders/" + orderId + "/chain").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lines[0].copyJob.jobCode").value("job-abc"))
        .andExpect(jsonPath("$.data.lines[0].link.kolItemId").value("1"))
        .andExpect(jsonPath("$.data.lines[0].link.linkCode").value("KPL-000000000001"))
        .andExpect(jsonPath("$.data.lines[0].link.sourceProductId").value("1"))
        .andExpect(jsonPath("$.data.lines[0].link.targetItemId").value("90"))
        .andExpect(jsonPath("$.data.lines[0].link.kolCode").value("KOL-AAA100"))
        .andExpect(jsonPath("$.data.lines[0].quantity").value(1))
        .andExpect(jsonPath("$.data.lines[0].payAmount").value("10.00"))
        .andExpect(jsonPath("$.data.lines[0].refunds[0].refundNo").value("R1"))
        .andExpect(jsonPath("$.data.lines[0].refunds[0].refundStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.data.lines[0].ledger").isEmpty())
        .andExpect(jsonPath("$.data.lines[0].withdrawals").isEmpty());
  }

  @Test
  void shouldChainWithoutCopyJobAndRefunds_whenLineHasNoJobOrRefunds() throws Exception {
    mapping(1, 100, "90", null, PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    mvc.perform(get("/admin-api/order/orders/" + orderId + "/chain").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lines[0].copyJob").isEmpty())
        .andExpect(jsonPath("$.data.lines[0].refunds").isEmpty());
  }

  @Test
  void shouldPageAndDetailRefunds_withFiltersAndOwnScope() throws Exception {
    mapping(1, 100, "90", "https://img.example/p1.png", PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    long itemId = jdbc.queryForObject("SELECT id FROM crm_order_item", Long.class);
    long refundId = refund(1, orderId, itemId, "R1", "SUCCESS", "RETURN_REFUND", "5.00");

    mvc.perform(
            get("/admin-api/order/refunds")
                .param("refundNo", "R1")
                .param("channel", "WEIDIAN")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].refundNo").value("R1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"))
        .andExpect(jsonPath("$.data.items[0].refundType").value("RETURN_REFUND"))
        .andExpect(jsonPath("$.data.items[0].refundStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items[0].applyTime").value(PAID.plusSeconds(60).toString()))
        .andExpect(jsonPath("$.data.items[0].shopCode").value("SHOP-000010"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL-AAA100"));
    mvc.perform(
            get("/admin-api/order/refunds")
                .param("refundStatus", "PROCESSING")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/order/refunds/" + refundId).header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.merchantNo").value("10"))
        .andExpect(jsonPath("$.data.shopCode").value("SHOP-000010"))
        .andExpect(jsonPath("$.data.lines[0].imageUrl").value("https://img.example/p1.png"))
        .andExpect(jsonPath("$.data.lines[0].refundAmount").value("5.00"));

    // OWN scope: the BD sees the order's refund after the snapshot points at them.
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "t40_bd",
                            "password",
                            PASSWORD,
                            "nickname",
                            "Test BD",
                            "role",
                            "STAFF",
                            "dataScope",
                            "OWN",
                            "postTemplate",
                            "BD",
                            "permissions",
                            List.of("order:view")))))
        .andExpect(status().isOk());
    long bd = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='t40_bd'", Long.class);
    String token = CompanySessions.login(mvc, json, "t40_bd", PASSWORD);
    mvc.perform(get("/admin-api/order/refunds").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    jdbc.update("UPDATE crm_order SET bd_user_id=? WHERE id=?", bd, orderId);
    mvc.perform(get("/admin-api/order/refunds").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"));
  }

  @Test
  void shouldMonitorSourcesMissingFieldsFailuresAndImports() throws Exception {
    mapping(1, 100, "90", null, PAID.minusSeconds(1), null);
    long orderId = save("1000", PAID, line("90", "1"));
    // Unlinked refund: one missing orderItemId; orders source stays API.
    refund(1, orderId, null, "R1", "SUCCESS", "REFUND_ONLY", "5.00");
    // A failed synchronization writes an ORDER_SYNC failure row.
    when(remote.orderList(anyLong(), any())).thenThrow(new IllegalStateException("boom"));
    TenantScope.runCompany(1, () -> assertThat(sync.synchronize(10)).isFalse());
    // An import with a data cutoff marks the shop (the failed sync above created the row).
    jdbc.update(
        "UPDATE crm_order_sync_state SET last_import_kind='ORDER', last_import_cutoff_at=?, last_import_task_id=77 WHERE shop_id=10",
        Timestamp.from(PAID.plusSeconds(300)));

    mvc.perform(get("/admin-api/order/sync-states").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data[0].orders.source").value("API"))
        .andExpect(jsonPath("$.data[0].orders.failures24h").value(1))
        .andExpect(jsonPath("$.data[0].orders.missingFields.payTime").value(0))
        .andExpect(jsonPath("$.data[0].refunds.missingFields.orderItemId").value(1))
        .andExpect(jsonPath("$.data[0].lastImport.kind").value("ORDER"))
        .andExpect(
            jsonPath("$.data[0].lastImport.dataCutoffTime").value(PAID.plusSeconds(300).toString()))
        .andExpect(jsonPath("$.data[0].lastImport.taskId").value("77"));

    mvc.perform(get("/admin-api/order/sync-failures").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].kind").value("ORDER_SYNC"))
        .andExpect(jsonPath("$.data[0].shopName").value("Main shop"))
        .andExpect(jsonPath("$.data[0].taskId").isEmpty());
  }

  @Test
  void shouldReportMissingOrderFields_whenOrdersLackPaymentTime() throws Exception {
    long unpaid = save("1000", null, line("90", "1"));
    assertThat(unpaid).isPositive();
    mvc.perform(get("/admin-api/order/sync-states").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orders.missingFields.payTime").value(1));
  }

  @Test
  void shouldExplainUnmatchedLines_withMappingsAndReasons() throws Exception {
    // No mapping at all for item 95: NO_MAPPING.
    long noMappingOrder = save("1000", PAID, line("95", "1"));
    long noMappingItem = jdbc.queryForObject("SELECT id FROM crm_order_item", Long.class);
    mvc.perform(
            get("/admin-api/order/unmatched/" + noMappingItem + "/evidence")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.weidianItemId").value("95"))
        .andExpect(jsonPath("$.data.merchantNo").value("10"))
        .andExpect(jsonPath("$.data.unmatchedReason").value("NO_MAPPING"))
        .andExpect(jsonPath("$.data.mappings").isEmpty())
        .andExpect(jsonPath("$.data.product").isEmpty());

    // Mapping exists but ended before payment: MAPPING_EXPIRED.
    jdbc.update("DELETE FROM crm_order_item");
    jdbc.update("DELETE FROM crm_order");
    mapping(1, 100, "90", null, PAID.minusSeconds(100), PAID.minusSeconds(1));
    long expiredOrder = save("1001", PAID, line("90", "1"));
    long expiredItem = jdbc.queryForObject("SELECT id FROM crm_order_item", Long.class);
    mvc.perform(
            get("/admin-api/order/unmatched/" + expiredItem + "/evidence")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unmatchedReason").value("MAPPING_EXPIRED"))
        .andExpect(jsonPath("$.data.mappings[0].kolCode").value("KOL-AAA100"))
        .andExpect(jsonPath("$.data.mappings[0].linkCode").value("KPL-000000000001"))
        .andExpect(jsonPath("$.data.mappings[0].coversPayTime").value(false))
        .andExpect(jsonPath("$.data.product.productId").value("1"))
        .andExpect(jsonPath("$.data.product.productCode").value("P1"));

    // Two attributed KOLs on one order: CONFLICT.
    jdbc.update("DELETE FROM crm_order_item");
    jdbc.update("DELETE FROM crm_order");
    mapping(2, 100, "90", null, PAID.minusSeconds(1), null);
    mapping(3, 200, "91", null, PAID.minusSeconds(1), null);
    long conflict = save("1002", PAID, line("90", "1"), line("91", "2"));
    long conflictItem =
        jdbc.queryForObject(
            "SELECT id FROM crm_order_item WHERE order_id=? LIMIT 1", Long.class, conflict);
    mvc.perform(
            get("/admin-api/order/unmatched/" + conflictItem + "/evidence")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unmatchedReason").value("CONFLICT"));
  }

  @Test
  void shouldFilterByPlatformOrderTime_notPayOrSyncTime_withInclusiveFromExclusiveTo()
      throws Exception {
    mapping(1, 100, "90", null, Instant.parse("2026-10-01T00:00:00Z"), null);
    // Ordered on the 10th but paid on the 16th; the second is ordered later but paid earlier.
    saveAt("1000", "pay", Instant.parse("2026-10-10T00:00:00Z"), PAID, line("90", "1"));
    saveAt(
        "1001",
        "pay",
        Instant.parse("2026-10-20T00:00:00Z"),
        PAID.minusSeconds(86_400),
        line("90", "2"));

    // The order-time window ignores both pay times; the lower bound includes an exact match.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-20T00:00:00Z")
                .param("orderTimeTo", "2026-10-21T00:00:00Z")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1001"));
    // The upper bound excludes an order placed exactly at that instant.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-09T00:00:00Z")
                .param("orderTimeTo", "2026-10-10T00:00:00Z")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-10T00:00:00Z")
                .param("orderTimeTo", "2026-10-11T00:00:00Z")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"));
    // Both windows combine: only the first order lies in the order-time and pay-time ranges.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("orderTimeFrom", "2026-10-09T00:00:00Z")
                .param("orderTimeTo", "2026-10-11T00:00:00Z")
                .param("payFrom", PAID.minusSeconds(1).toString())
                .param("payTo", PAID.plusSeconds(1).toString())
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"));
  }

  @Test
  void shouldResolveKolKeywordOverExactCodeAndContainedName_includingDuplicateNames()
      throws Exception {
    jdbc.update("UPDATE crm_kol SET name='Alice' WHERE id=100");
    jdbc.update("UPDATE crm_kol SET name='alice b' WHERE id=200");
    kol(300, "KOL-CCC300", "Twin");
    kol(400, "KOL-DDD400", "Twin");
    // Every mapping is valid long before all pay times, so attribution never lapses.
    Instant early = Instant.parse("2026-10-01T00:00:00Z");
    mapping(1, 100, "90", null, early, null);
    mapping(2, 200, "91", null, early, null);
    mapping(3, 300, "92", null, early, null);
    mapping(4, 400, "93", null, early, null);
    saveAt("1000", "pay", PAID.minusSeconds(50), PAID, line("90", "1"));
    saveAt("1001", "pay", PAID.minusSeconds(50), PAID.minusSeconds(10), line("91", "2"));
    saveAt("1002", "pay", PAID.minusSeconds(50), PAID.minusSeconds(20), line("92", "3"));
    saveAt("1003", "pay", PAID.minusSeconds(50), PAID.minusSeconds(30), line("93", "4"));

    // Name match is case-insensitive and contains; several creators may share one name.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("kolKeyword", "alice")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("2"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("1001"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL-AAA100"))
        .andExpect(jsonPath("$.data.items[1].kolCode").value("KOL-AAA200"));
    // The code still matches exactly and keeps the legacy kolCode hit scope.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("kolKeyword", "KOL-AAA100")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("kolKeyword", "Twin")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("2"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1002"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("1003"));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("kolKeyword", "KOL-ZZZ999")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
  }

  @Test
  void shouldFilterRefundStates_oncePerOrder_acrossPartialAndFullRefunds() throws Exception {
    mapping(1, 100, "90", null, Instant.parse("2026-10-01T00:00:00Z"), null);
    // One order with three refund rows: two succeeded (partial, 8 of 10) plus one processing.
    long mixed = saveAt("1000", "pay", PAID.minusSeconds(50), PAID, line("90", "1"));
    long mixedItem =
        jdbc.queryForObject("SELECT id FROM crm_order_item WHERE order_id=?", Long.class, mixed);
    refund(1, mixed, mixedItem, "R1", "SUCCESS", "RETURN_REFUND", "5.00");
    refund(2, mixed, mixedItem, "R2", "SUCCESS", "REFUND_ONLY", "3.00");
    refund(3, mixed, null, "R3", "PROCESSING", "REFUND_ONLY", "2.00");
    long fullyRefunded =
        saveAt("1001", "pay", PAID.minusSeconds(50), PAID.minusSeconds(10), line("90", "2"));
    refund(4, fullyRefunded, null, "R4", "SUCCESS", "REFUND_ONLY", "10.00");
    long processingOnly =
        saveAt("1002", "pay", PAID.minusSeconds(50), PAID.minusSeconds(20), line("90", "3"));
    refund(5, processingOnly, null, "R5", "PROCESSING", "REFUND_ONLY", "1.00");
    long closedOnly =
        saveAt("1003", "pay", PAID.minusSeconds(50), PAID.minusSeconds(30), line("90", "4"));
    refund(6, closedOnly, null, "R6", "CLOSED", "REFUND_ONLY", "1.00");
    saveAt("1004", "pay", PAID.minusSeconds(50), PAID.minusSeconds(40), line("90", "5"));

    mvc.perform(
            get("/admin-api/order/orders")
                .param("refundState", "REFUNDING")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        // The mixed order counts once despite three rows; CLOSED never matches.
        .andExpect(jsonPath("$.data.total").value("2"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("1002"));
    mvc.perform(
            get("/admin-api/order/orders")
                .param("refundState", "REFUNDED")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        // Both partial and whole refunds match; the same order appears under both filters.
        .andExpect(jsonPath("$.data.total").value("2"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("1001"));
  }

  @Test
  void shouldFilterAbnormalOrders_asOneUnion_intersectedWithOtherFiltersAndScopes()
      throws Exception {
    shop(11);
    mapping(1, 100, "90", null, PAID.minusSeconds(1), null);
    long unknownStatus =
        saveAt("1000", "pay", Instant.parse("2026-10-10T00:00:00Z"), PAID, line("90", "1"));
    jdbc.update("UPDATE crm_order SET order_status='UNKNOWN' WHERE id=?", unknownStatus);
    long unmatched =
        saveAt("1001", "pay", Instant.parse("2026-10-11T00:00:00Z"), PAID, line("91", "2"));
    long conflict =
        saveAt("1002", "pay", Instant.parse("2026-10-12T00:00:00Z"), PAID, line("92", "3"));
    jdbc.update("UPDATE crm_order SET attribution_status='CONFLICT' WHERE id=?", conflict);
    jdbc.update("UPDATE crm_order SET shop_id=11 WHERE id=?", conflict);
    saveAt("1003", "pay", Instant.parse("2026-10-13T00:00:00Z"), PAID, line("90", "4"));
    // Another company's order never leaks into the union.
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,order_status,raw_status,attribution_status,source,order_time,pay_amount,total_amount,freight,discount,last_sync_time,create_time,update_time,is_deleted) "
            + "VALUES(99999,2,10,'t70_foreign','UNKNOWN','pay','UNMATCHED',2,now(),10,10,0,0,now(),now(),now(),FALSE)");

    mvc.perform(
            get("/admin-api/order/orders").param("abnormal", "true").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("3"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1002"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("1001"))
        .andExpect(jsonPath("$.data.items[2].orderNo").value("1000"));
    // Shop intersect: only the conflict order lives in shop 11.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("abnormal", "true")
                .param("shopId", "11")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1002"));
    // Order-time intersect: the window covers only the unmatched order.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("abnormal", "true")
                .param("orderTimeFrom", "2026-10-11T00:00:00Z")
                .param("orderTimeTo", "2026-10-12T00:00:00Z")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1001"));
    // Creator intersect: only the unknown-status order is attributed to KOL-AAA100.
    mvc.perform(
            get("/admin-api/order/orders")
                .param("abnormal", "true")
                .param("kolKeyword", "KOL-AAA100")
                .header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1000"));

    // OWN scope: the BD sees only the abnormal order their snapshot owns.
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "t70_bd",
                            "password",
                            PASSWORD,
                            "nickname",
                            "Task 70 BD",
                            "role",
                            "STAFF",
                            "dataScope",
                            "OWN",
                            "postTemplate",
                            "BD",
                            "permissions",
                            List.of("order:view")))))
        .andExpect(status().isOk());
    long bd = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='t70_bd'", Long.class);
    jdbc.update("UPDATE crm_order SET bd_user_id=? WHERE id=?", bd, unmatched);
    String token = CompanySessions.login(mvc, json, "t70_bd", PASSWORD);
    mvc.perform(
            get("/admin-api/order/orders").param("abnormal", "true").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("1001"));
  }

  @Test
  void shouldIgnoreSoftDeletedOrders_whenResolvingLatestOrderSource() throws Exception {
    long apiOrder = save("1000", PAID, line("90", "1"));
    assertThat(apiOrder).isPositive();
    // Soft-delete the only API order, then plant a newer IMPORT row with a higher id.
    jdbc.update("UPDATE crm_order SET is_deleted = TRUE WHERE id=?", apiOrder);
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,order_status,raw_status,attribution_status,source,order_time,pay_amount,total_amount,freight,discount,last_sync_time,create_time,update_time,is_deleted) "
            + "VALUES(?,1,10,'1001','PAID','pay','UNMATCHED',2,now(),10,10,0,0,now(),now(),now(),FALSE)",
        apiOrder + 1);
    mvc.perform(get("/admin-api/order/sync-states").header("X-Admin-Token", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orders.source").value("IMPORT"));
  }
}
