package com.tcrm.admin.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.service.KolWriter;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 64 against real PostgreSQL and Redis: the creator order list with the new fuzzy filters, and
 * the workbench overview. Both surfaces answer only the signed-in creator's own attributed orders
 * in the session company; commission amounts stay at the contract placeholders until task 62.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolOrderIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String KOL_TOKEN = "X-Kol-Token";
  private static final Instant PAY_A = Instant.parse("2026-10-15T08:00:00Z");
  private static final Instant PAY_B = Instant.parse("2026-10-16T08:00:00Z");

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

  @MockitoBean WeidianBusinessApi remote;

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final OrderSyncCoordinator sync;
  private final KolWriter writer;
  private final KolAccountStore accounts;
  private final BCryptPasswordEncoder passwords;
  private final StringRedisTemplate redis;

  @Autowired
  KolOrderIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      OrderSyncCoordinator sync,
      KolWriter writer,
      KolAccountStore accounts,
      BCryptPasswordEncoder passwords,
      StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.sync = sync;
    this.writer = writer;
    this.accounts = accounts;
    this.passwords = passwords;
    this.redis = redis;
  }

  private long kolA;
  private long kolB;

  @BeforeEach
  void prepareIndependentData() {
    sync.setScheduled(false);
    for (String table :
        List.of(
            "crm_order_item",
            "crm_order",
            "crm_kol_auth_token",
            "crm_kol_bd_history",
            "crm_kol_item",
            "crm_shop",
            "sys_oper_log")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_code,shop_name,weidian_shop_id,create_time,update_time)"
            + " VALUES(10,1,'SHOP-000010','Main shop','10',now(),now())");
    kolA = activatedKol(1L, "a@example.test");
    kolB = activatedKol(1L, "b@example.test");
    // One order for the creator under test: two own lines plus another creator's line.
    order(1001, 1L, "W99000101", kolA, "PAID", PAY_A);
    item(2001, 1001, 1L, kolA, "Alpha Widget", "100.00");
    item(2002, 1001, 1L, kolA, "Beta Gadget", "50.00");
    item(2003, 1001, 1L, kolB, "Gamma Thing", "70.00");
    // Another creator's order, an unpaid one of the creator under test, and the same creator id in
    // another company: only the first may surface for kolA.
    order(1002, 1L, "W99000102", kolB, "PAID", PAY_B);
    item(2004, 1002, 1L, kolB, "Alpha Widget", "30.00");
    order(1003, 1L, "W99000103", kolA, "UNPAID", null);
    order(1004, 2L, "W99000104", kolA, "PAID", PAY_A);
    item(2005, 1004, 2L, kolA, "Other Company", "10.00");
  }

  @Test
  void shouldListOnlyOwnAttributedOrders_withCaseInsensitiveFuzzyFilters() throws Exception {
    String token = kolLogin("a@example.test");

    mvc.perform(get("/kol-api/orders").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2))
        // Unpaid orders have no pay time and sort first; order numbers stay masked.
        .andExpect(jsonPath("$.data.items[0].orderNo").value("W990****0103"))
        .andExpect(jsonPath("$.data.items[0].payAmount").value("0.00"))
        .andExpect(jsonPath("$.data.items[1].orderNo").value("W990****0101"))
        .andExpect(jsonPath("$.data.items[1].payAmount").value("150.00"))
        .andExpect(jsonPath("$.data.items[1].refundAmount").value("0.00"))
        .andExpect(jsonPath("$.data.items[1].items.length()").value(2));

    mvc.perform(get("/kol-api/orders").param("orderNo", "0101").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].payAmount").value("150.00"));
    mvc.perform(get("/kol-api/orders").param("orderNo", "w99000101").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
    // The product name matches only the creator's own lines, not the shared order's other items.
    mvc.perform(get("/kol-api/orders").param("productName", "gamma").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
    mvc.perform(get("/kol-api/orders").param("productName", "ALPHA").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].orderNo").value("W990****0101"));
    mvc.perform(
            get("/kol-api/orders")
                .param("orderNo", "99000101")
                .param("productName", "beta")
                .header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
    // Another company's order with the same kol id never leaks through either surface.
    mvc.perform(
            get("/kol-api/orders").param("productName", "Other Company").header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  void shouldKeepTheWorkbenchOverviewOwnAndInCompany() throws Exception {
    String token = kolLogin("a@example.test");

    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "2026-10-15T00:00:00Z")
                .param("to", "2026-10-17T00:00:00Z")
                .header(KOL_TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.salesCny").value("150.00"))
        .andExpect(jsonPath("$.data.paidOrderCount").value(1))
        .andExpect(jsonPath("$.data.commissionCny").value("0.00"))
        .andExpect(jsonPath("$.data.withdrawnCny").value("0.00"))
        .andExpect(jsonPath("$.data.withdrawingCny").value("0.00"))
        .andExpect(jsonPath("$.data.frozenCny").value("0.00"))
        .andExpect(jsonPath("$.data.daily.length()").value(1))
        .andExpect(jsonPath("$.data.daily[0].date").value("2026-10-15"))
        .andExpect(jsonPath("$.data.daily[0].salesCny").value("150.00"))
        .andExpect(jsonPath("$.data.daily[0].commissionCny").value("0.00"));
  }

  @Test
  void shouldRejectBadAnalyticsRanges() throws Exception {
    String token = kolLogin("a@example.test");
    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "2026-10-17T00:00:00Z")
                .param("to", "2026-10-15T00:00:00Z")
                .header(KOL_TOKEN, token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/kol-api/analytics/overview")
                .param("from", "yesterday")
                .param("to", "2026-10-17T00:00:00Z")
                .header(KOL_TOKEN, token))
        .andExpect(status().isBadRequest());
  }

  private long activatedKol(long tenantId, String email) {
    KolDO kol = new KolDO();
    kol.setName("Kol " + email);
    kol.setEmail(email);
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setStatus(1);
    TenantScope.runCompany(tenantId, () -> writer.insert(kol, null, 1L));
    accounts.activate(kol.getId(), passwords.encode("some-password-1"));
    return kol.getId();
  }

  private void order(
      long id, long tenantId, String orderNo, long kolId, String status, Instant payTime) {
    Instant orderTime = payTime == null ? PAY_A : payTime.minusSeconds(3600);
    jdbc.update(
        "INSERT INTO crm_order(id,tenant_id,shop_id,weidian_order_id,kol_id,attribution_status,"
            + "order_status,raw_status,order_time,pay_time,total_amount,pay_amount,freight,"
            + "discount,source,last_sync_time,create_time,update_time,is_deleted)"
            + " VALUES(?,?,10,?,?,'ATTRIBUTED',?,'10',?,?,?,?,0,0,1,now(),now(),now(),false)",
        id,
        tenantId,
        orderNo,
        kolId,
        status,
        Timestamp.from(orderTime),
        payTime == null ? null : Timestamp.from(payTime),
        150.00,
        150.00);
  }

  private void item(
      long id, long orderId, long tenantId, long kolId, String name, String payAmount) {
    jdbc.update(
        "INSERT INTO crm_order_item(id,order_id,tenant_id,kol_id,sub_order_id,weidian_item_id,"
            + "sku_id,item_name,sku_name,quantity,pay_amount,refund_amount,attribution_type,"
            + "create_time,update_time,is_deleted)"
            + " VALUES(?,?,?,?,?,?,?,?,?,'General',1,?,0,1,now(),now(),false)",
        id,
        orderId,
        tenantId,
        kolId,
        "sub-" + id,
        String.valueOf(8000L + id),
        "0",
        name,
        payAmount);
  }

  private String kolLogin(String email) throws Exception {
    JsonNode login =
        json.readTree(
            mvc.perform(
                    post("/kol-api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"some-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return login.at("/data/token").asString();
  }
}
