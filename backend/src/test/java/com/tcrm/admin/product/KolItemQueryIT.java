package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.CompanySessions.OpenedCompany;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkCodeDTO;
import com.tcrm.admin.shared.TenantScope;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 39 against PostgreSQL: link code, filters, OWN scope, company isolation, sales, cleanup and
 * visits.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolItemQueryIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String LINK = "KPL-AAAAAAAAAAAA";

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

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final DataSource dataSource;
  private final KolItemApi kolItems;

  private String adminToken;
  private String bdToken;
  private OpenedCompany companyB;
  private long bdUserId;

  @Autowired
  KolItemQueryIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      DataSource dataSource,
      KolItemApi kolItems) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.dataSource = dataSource;
    this.kolItems = kolItems;
  }

  @BeforeEach
  void prepare() throws Exception {
    for (String table :
        List.of(
            "crm_click_log",
            "crm_kol_cleanup_item",
            "crm_order_item",
            "crm_order",
            "crm_kol_item",
            "crm_product",
            "crm_shop",
            "crm_kol_bd_history",
            "crm_kol")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    bdUserId =
        CompanySessions.createStaff(mvc, json, adminToken, PASSWORD, "t39-bd", "BD", "OWN", null);
    bdToken = CompanySessions.login(mvc, json, "t39-bd", PASSWORD);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    companyB = CompanySessions.openCompany(mvc, json, PASSWORD, "b" + suffix, "adm" + suffix);
    seed();
  }

  @Test
  void shouldFilterAndSummarize_forTheCompany() throws Exception {
    JsonNode page = list(adminToken, "");
    assertThat(page.get("total").asText()).isEqualTo("5");
    Map<String, JsonNode> rows = byId(page.get("items"));
    JsonNode sold = rows.get("9301");
    assertThat(sold.get("linkCode").asText()).isEqualTo(LINK);
    assertThat(sold.get("itemName").asText()).isEqualTo("t39 With Image");
    assertThat(sold.get("imageUrl").asText()).isEqualTo("https://example.com/t39.png");
    assertThat(sold.get("kolCode").asText()).isEqualTo("KOL-TAA001");
    assertThat(sold.get("targetShopName").asText()).isEqualTo("t39 Shop A");
    assertThat(sold.get("targetShopCode").asText()).isEqualTo("SHOP-009101");
    assertThat(sold.get("targetChannel").asText()).isEqualTo("WEIDIAN");
    assertThat(sold.get("targetItemId").asText()).isEqualTo("90010001");
    assertThat(sold.get("targetItemUrl").asText())
        .isEqualTo("https://weidian.com/item.html?itemID=90010001");
    assertThat(sold.get("sourceItemId").asText()).isEqualTo("80010001");
    assertThat(sold.get("sourceChannel").asText()).isEqualTo("WEIDIAN");
    assertThat(sold.get("currentRate").asText()).isEqualTo("0.1500");
    assertThat(sold.get("salesQuantity").asText()).isEqualTo("3");
    assertThat(sold.get("payAmount").asText()).isEqualTo("14.00");
    assertThat(sold.get("status").asText()).isEqualTo("ENABLED");
    assertThat(sold.get("cleanupStatus").asText()).isEqualTo("FAILED");

    assertThat(rows.get("9302").get("imageUrl").isNull()).isTrue();
    assertThat(rows.get("9302").get("salesQuantity").asText()).isEqualTo("0");
    assertThat(rows.get("9302").get("payAmount").asText()).isEqualTo("0.00");
    assertThat(rows.get("9302").get("cleanupStatus").asText()).isEqualTo("PENDING");
    assertThat(rows.get("9303").get("status").asText()).isEqualTo("DISABLED");
    assertThat(rows.get("9303").get("cleanupStatus").asText()).isEqualTo("DONE");
    assertThat(rows.get("9303").get("currentRate").isNull()).isTrue();
    assertThat(rows.get("9304").get("itemName").asText()).isEqualTo("t39 Snapshot");
    assertThat(rows.get("9304").get("sourceChannel").isNull()).isTrue();
    assertThat(rows.get("9304").get("cleanupStatus").asText()).isEqualTo("NONE");

    assertThat(list(adminToken, "linkCode=" + LINK).get("items").get(0).get("id").asText())
        .isEqualTo("9301");
    assertThat(list(adminToken, "kolKeyword=alice").get("total").asText()).isEqualTo("4");
    assertThat(list(adminToken, "kolKeyword=KOL-TBB001").get("items").get(0).get("id").asText())
        .isEqualTo("9303");
    assertThat(list(adminToken, "kolKeyword=T39A@EXAMPLE.TEST").get("total").asText())
        .isEqualTo("4");
    assertThat(list(adminToken, "kolKeyword=nobody").get("total").asText()).isEqualTo("0");
    assertThat(list(adminToken, "shopId=9102").get("items").get(0).get("id").asText())
        .isEqualTo("9302");

    JsonNode detail = detail(adminToken, 9301);
    assertThat(detail.get("visitCount").asText()).isEqualTo("1");
    assertThat(detail.get("totalPayAmount").asText()).isEqualTo("14.00");
    assertThat(detail.get("refundAmount").asText()).isEqualTo("3.50");
    assertThat(detail.get("sourceItemUrl").asText())
        .isEqualTo("https://weidian.com/item.html?itemID=80010001");
    assertThat(detail.get("cleanupReason").asText()).isEqualTo("t39 newer");
    assertThat(detail.get("cleanupFailReason").asText()).isEqualTo("t39 fail");
    JsonNode bare = detail(adminToken, 9304);
    assertThat(bare.get("visitCount").asText()).isEqualTo("0");
    assertThat(bare.get("sourceItemUrl").isNull()).isTrue();
    assertThat(bare.get("cleanupTime").isNull()).isTrue();

    assertThat(list(bdToken, "").get("total").asText()).isEqualTo("4");
    mvc.perform(get("/admin-api/product/kol-items/9303").header(TOKEN, bdToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));

    assertThat(list(companyB.token(), "").get("items").get(0).get("id").asText()).isEqualTo("9306");
    assertThat(detail(companyB.token(), 9306).get("payAmount").asText()).isEqualTo("90.00");
    mvc.perform(get("/admin-api/product/kol-items/9301").header(TOKEN, companyB.token()))
        .andExpect(status().isNotFound());

    try (var scope = TenantScope.openCompany(1L)) {
      assertThat(kolItems.listLinkCodesByIds(List.of(9301L, 9306L, 999L)))
          .extracting(KolItemLinkCodeDTO::linkCode)
          .containsExactly(LINK);
      assertThat(kolItems.listLinkCodesByIds(List.of())).isEmpty();
    }
    String generated =
        jdbc.queryForObject("SELECT link_code FROM crm_kol_item WHERE id = 9307", String.class);
    assertThat(generated).matches("KPL-[0-9A-F]{12}");
  }

  @Test
  void shouldUseTheOrderItemIndex() throws Exception {
    String plan =
        explain(
            """
            SELECT oi.kol_item_id
            FROM crm_order_item oi
            JOIN crm_order o ON o.id = oi.order_id AND o.is_deleted = FALSE
            WHERE oi.is_deleted = FALSE AND oi.kol_item_id IN (9301)
            GROUP BY oi.kol_item_id
            """);
    assertThat(plan).contains("idx_crm_order_item_kol_item");
  }

  private JsonNode list(String token, String query) throws Exception {
    String url = "/admin-api/product/kol-items" + (query.isEmpty() ? "" : "?" + query);
    String body =
        mvc.perform(get(url).header(TOKEN, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("data");
  }

  private JsonNode detail(String token, long id) throws Exception {
    String body =
        mvc.perform(get("/admin-api/product/kol-items/" + id).header(TOKEN, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("data");
  }

  private static Map<String, JsonNode> byId(JsonNode items) {
    Map<String, JsonNode> rows = new HashMap<>();
    items.forEach(row -> rows.put(row.get("id").asText(), row));
    return rows;
  }

  private String explain(String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET enable_seqscan = off");
      try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
        StringBuilder plan = new StringBuilder();
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
        return plan.toString();
      }
    }
  }

  private void seed() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    shop(9101, 1L, "t39 Shop A", "91010001");
    shop(9102, 1L, "t39 Shop B", "91020001");
    shop(9103, companyB.tenantId(), "t39 Shop C", "91030001");
    kol(9111, 1L, "KOL-TAA001", "t39 Alice", "t39a@example.test", bdUserId, "0.1500");
    kol(9112, 1L, "KOL-TBB001", "t39 Bob", "t39b@example.test", null, null);
    kol(9113, companyB.tenantId(), "KOL-TCC001", "t39 Cara", "t39c@example.test", null, null);
    product(9201, 1L, "P91001", 9101, "80010001", "t39 With Image", "https://example.com/t39.png");
    product(9202, 1L, "P91002", 9101, "80010002", "t39 No Image", null);
    mapping(9301, 1L, 9111, 9201L, 9101, "90010001", null, LINK, now, null, now);
    mapping(
        9302,
        1L,
        9111,
        9202L,
        9102,
        "90010002",
        null,
        "KPL-BBBBBBBBBBBB",
        now.minusSeconds(3600),
        null,
        now.minusSeconds(10));
    mapping(
        9303,
        1L,
        9112,
        9201L,
        9101,
        "90010003",
        null,
        "KPL-CCCCCCCCCCCC",
        now.minusSeconds(86400),
        now.minusSeconds(3600),
        now.minusSeconds(20));
    mapping(
        9304,
        1L,
        9111,
        null,
        9101,
        "90010004",
        "t39 Snapshot",
        "KPL-DDDDDDDDDDDD",
        now,
        null,
        now.minusSeconds(30));
    jdbc.update(
        """
        INSERT INTO crm_kol_item(id, tenant_id, kol_id, shop_id, weidian_item_id, source,
          valid_from, create_time, update_time)
        VALUES (9307, 1, 9111, 9101, '90010007', 'IMPORT', ?, ?, ?)
        """,
        java.sql.Timestamp.from(now.minusSeconds(86400)),
        java.sql.Timestamp.from(now.minusSeconds(40)),
        java.sql.Timestamp.from(now));
    mapping(
        9306, companyB.tenantId(), 9113, null, 9103, "90010006", "t39 Other", LINK, now, null, now);

    order(9401, 1L, 9101, "8001", 9111, "PAID");
    line(9501, 1L, 9401, "s1", 2, "10.00", "1.50", 9111, 9201L, 9301L);
    order(9402, 1L, 9101, "8002", 9111, "COMPLETED");
    line(9502, 1L, 9402, "s1", 1, "4.00", "0.00", 9111, 9201L, 9301L);
    order(9403, 1L, 9101, "8003", 9111, "UNPAID");
    line(9503, 1L, 9403, "s1", 8, "80.00", "0.00", 9111, 9201L, 9301L);
    order(9404, 1L, 9101, "8004", 9111, "CLOSED");
    line(9504, 1L, 9404, "s1", 3, "30.00", "2.00", 9111, 9201L, 9301L);
    order(9405, companyB.tenantId(), 9103, "8005", 9113, "PAID");
    line(9505, companyB.tenantId(), 9405, "s1", 9, "90.00", "9.00", 9113, null, 9306L);

    cleanup(9601, 1L, 9111, 9301, 9101, "SUCCESS", "t39 older", null, now.minusSeconds(7200));
    cleanup(9602, 1L, 9111, 9301, 9101, "FAILED", "t39 newer", "t39 fail", now.minusSeconds(60));
    cleanup(9603, 1L, 9111, 9302, 9102, "PENDING", "t39 wait", null, now);
    cleanup(9604, 1L, 9112, 9303, 9101, "SUCCESS", "t39 done", null, now);

    click(9701, 1L, 9111, 9201, 9301, false, false, now);
    click(9702, 1L, 9111, 9201, 9301, true, false, now);
    click(9703, 1L, 9111, 9201, 9301, false, true, now);
    click(9704, companyB.tenantId(), 9113, 9201, 9306, false, false, now);
  }

  private void shop(long id, long tenantId, String name, String weidianId) {
    String shopCode = "SHOP-" + String.format("%06d", id % 1_000_000);
    jdbc.update(
        """
        INSERT INTO crm_shop(id, tenant_id, shop_code, channel, shop_name, weidian_shop_id, status,
          is_source, copy_pool_status, max_items, current_items, create_time, update_time)
        VALUES (?, ?, ?, 'WEIDIAN', ?, ?, 1, FALSE, 'EXITED', 20000, 0, now(), now())
        """,
        id,
        tenantId,
        shopCode,
        name,
        weidianId);
  }

  private void kol(
      long id, long tenantId, String code, String name, String email, Long bdUserId, String rate) {
    jdbc.update(
        """
        INSERT INTO crm_kol(id, tenant_id, kol_code, name, email, country, bd_user_id, default_rate,
          create_time, update_time)
        VALUES (?, ?, ?, ?, ?, 'US', ?, ?, now(), now())
        """,
        id,
        tenantId,
        code,
        name,
        email,
        bdUserId,
        rate == null ? null : new java.math.BigDecimal(rate));
  }

  private void product(
      long id,
      long tenantId,
      String code,
      long shopId,
      String weidianId,
      String name,
      String image) {
    jdbc.update(
        """
        INSERT INTO crm_product(id, tenant_id, product_code, shop_id, weidian_item_id, name,
          image_url, price, create_time, update_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, 10, now(), now())
        """,
        id,
        tenantId,
        code,
        shopId,
        weidianId,
        name,
        image);
  }

  private void mapping(
      long id,
      long tenantId,
      long kolId,
      Long productId,
      long shopId,
      String weidianId,
      String itemName,
      String linkCode,
      Instant validFrom,
      Instant validTo,
      Instant createTime) {
    jdbc.update(
        """
        INSERT INTO crm_kol_item(id, tenant_id, kol_id, product_id, shop_id, weidian_item_id,
          item_name, source, link_code, valid_from, valid_to, create_time, update_time)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'IMPORT', ?, ?, ?, ?, ?)
        """,
        id,
        tenantId,
        kolId,
        productId,
        shopId,
        weidianId,
        itemName,
        linkCode,
        java.sql.Timestamp.from(validFrom),
        validTo == null ? null : java.sql.Timestamp.from(validTo),
        java.sql.Timestamp.from(createTime),
        java.sql.Timestamp.from(createTime));
  }

  private void order(
      long id, long tenantId, long shopId, String weidianOrderId, long kolId, String status) {
    jdbc.update(
        """
        INSERT INTO crm_order(id, tenant_id, shop_id, weidian_order_id, kol_id, attribution_status,
          order_status, raw_status, order_time, pay_time, total_amount, pay_amount, freight, discount,
          source, last_sync_time, create_time, update_time)
        VALUES (?, ?, ?, ?, ?, 'ATTRIBUTED', ?, ?, now(), now(), 0, 0, 0, 0, 1, now(), now(), now())
        """,
        id,
        tenantId,
        shopId,
        weidianOrderId,
        kolId,
        status,
        status);
  }

  private void line(
      long id,
      long tenantId,
      long orderId,
      String subOrderId,
      int quantity,
      String pay,
      String refund,
      long kolId,
      Long productId,
      long kolItemId) {
    jdbc.update(
        """
        INSERT INTO crm_order_item(id, tenant_id, order_id, sub_order_id, weidian_item_id, sku_id,
          item_name, sku_name, quantity, pay_amount, refund_amount, kol_id, product_id, kol_item_id,
          attribution_type, create_time, update_time)
        VALUES (?, ?, ?, ?, '90010001', '', 'Item', '', ?, ?, ?, ?, ?, ?, 1, now(), now())
        """,
        id,
        tenantId,
        orderId,
        subOrderId,
        quantity,
        new java.math.BigDecimal(pay),
        new java.math.BigDecimal(refund),
        kolId,
        productId,
        kolItemId);
  }

  private void cleanup(
      long id,
      long tenantId,
      long kolId,
      long kolItemId,
      long shopId,
      String status,
      String reason,
      String failReason,
      Instant cleanupTime) {
    jdbc.update(
        """
        INSERT INTO crm_kol_cleanup_item(id, tenant_id, kol_id, kol_code, kol_item_id, shop_id,
          weidian_item_id, status, trigger_type, reason, fail_reason, cleanup_time)
        VALUES (?, ?, ?, 'KOL-TAA001', ?, ?, '90010001', ?, 'MANUAL', ?, ?, ?)
        """,
        id,
        tenantId,
        kolId,
        kolItemId,
        shopId,
        status,
        reason,
        failReason,
        java.sql.Timestamp.from(cleanupTime));
  }

  private void click(
      long id,
      long tenantId,
      long kolId,
      long productId,
      long kolItemId,
      boolean bot,
      boolean dup,
      Instant clickTime) {
    jdbc.update(
        """
        INSERT INTO crm_click_log(id, click_time, kol_id, product_id, kol_item_id, ip_hash,
          is_bot, is_dup, create_time, tenant_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
        """,
        id,
        java.sql.Timestamp.from(clickTime),
        kolId,
        productId,
        kolItemId,
        String.format("%064d", id),
        bot,
        dup,
        tenantId);
  }
}
