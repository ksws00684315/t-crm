package com.tcrm.admin.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.order.service.OrderNormalizer;
import com.tcrm.admin.order.service.OrderSnapshotCorrection;
import com.tcrm.admin.order.service.OrderSync;
import com.tcrm.admin.order.service.OrderSyncCoordinator;
import com.tcrm.admin.order.service.OrderWriter;
import com.tcrm.admin.product.api.OrderMappingApi;
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

@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderIT {
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
  private final OrderMappingApi mappings;
  private final KolOwnershipApi ownership;
  private final OrderSnapshotCorrection correction;
  private final OrderSyncCoordinator coordinator;
  private final StringRedisTemplate redis;
  private final WeidianAuthorizationApi authorization;

  @Autowired
  OrderIT(
      JdbcTemplate jdbc,
      MockMvc mvc,
      ObjectMapper json,
      OrderWriter writer,
      OrderNormalizer normalizer,
      OrderSync sync,
      OrderMappingApi mappings,
      KolOwnershipApi ownership,
      OrderSnapshotCorrection correction,
      OrderSyncCoordinator coordinator,
      StringRedisTemplate redis,
      WeidianAuthorizationApi authorization,
      org.redisson.api.RedissonClient redisson) {
    this.jdbc = jdbc;
    this.mvc = mvc;
    this.json = json;
    this.writer = writer;
    this.normalizer = normalizer;
    this.sync = sync;
    this.mappings = mappings;
    this.ownership = ownership;
    this.correction = correction;
    this.coordinator = coordinator;
    this.redis = redis;
    this.authorization = authorization;
    this.redisson = redisson;
  }

  private final org.redisson.api.RedissonClient redisson;
  private String admin;

  @BeforeEach
  void prepare() throws Exception {
    coordinator.setScheduled(false);
    correction.setScheduled(false);
    reset(remote);
    redis.delete("order:bd-snapshot:checkpoint");
    for (String table :
        List.of(
            "crm_refund",
            "crm_order_item",
            "crm_order",
            "crm_order_sync_state",
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
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
    shop(10, 1);
    shop(20, 2);
    kol(100, "KOL0100", 1);
    kol(200, "KOL0200", 1);
    kol(300, "KOL0300", 2);
    jdbc.update(
        "INSERT INTO crm_weidian_shop_auth(id,tenant_id,shop_id,auth_mode,status,create_time,update_time) VALUES(10,1,10,'SERVICE','AUTHORIZED',now(),now())");
    when(remote.orderList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.OrderListResponse(0, 0, List.of(), false, null, null));
    when(remote.refundList(anyLong(), any()))
        .thenReturn(new WeidianBusinessApi.RefundListResponse(List.of(), 0, null));
  }

  private void shop(long id, long tenant) {
    jdbc.update(
        "INSERT INTO crm_shop(id,tenant_id,shop_name,weidian_shop_id,create_time,update_time) VALUES(?,?,?,?,now(),now())",
        id,
        tenant,
        "Test shop",
        String.valueOf(id));
  }

  private void kol(long id, String code, long tenant) {
    jdbc.update(
        "INSERT INTO crm_kol(id,tenant_id,kol_code,name,email,country,create_time,update_time) VALUES(?,?,?,?,?,'US',now(),now())",
        id,
        tenant,
        code,
        "Test creator",
        code.toLowerCase() + "@example.test");
  }

  private void mapping(long id, long kol, String item, Instant from, Instant to) {
    jdbc.update(
        "INSERT INTO crm_product(id,tenant_id,product_code,shop_id,weidian_item_id,name,price,create_time,update_time) VALUES(?,1,?,10,?,'Test original',10,now(),now()) ON CONFLICT DO NOTHING",
        id,
        "P" + id,
        "8" + id);
    jdbc.update(
        "INSERT INTO crm_kol_item(id,tenant_id,kol_id,product_id,shop_id,weidian_item_id,valid_from,valid_to,create_time,update_time) VALUES(?,1,?,?,10,?,?,?,now(),now())",
        id,
        kol,
        id,
        item,
        Timestamp.from(from),
        to == null ? null : Timestamp.from(to));
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

  private WeidianBusinessApi.OrderDetailResponse detail(
      String id, Instant paid, WeidianBusinessApi.OrderLine... lines) {
    return new WeidianBusinessApi.OrderDetailResponse(
        id,
        "pay",
        "",
        BigDecimal.TEN,
        BigDecimal.TEN,
        PAID.minusSeconds(100),
        paid,
        List.of(lines),
        json.readTree(
            "{\"buyer_info\":{\"buyer_id\":\"test-buyer\",\"name\":\"NEVER-STORE\",\"phone\":\"NEVER-STORE\",\"address\":\"NEVER-STORE\"}}"));
  }

  private long save(String id, Instant paid, WeidianBusinessApi.OrderLine... lines) {
    TenantScope.runCompany(
        1, () -> writer.save(normalizer.normalize(10, detail(id, paid, lines), PAID)));
    return jdbc.queryForObject("SELECT id FROM crm_order WHERE weidian_order_id=?", Long.class, id);
  }

  private void history(long id, long kol, Long bd, Instant from, Instant to) {
    jdbc.update(
        "INSERT INTO crm_kol_bd_history(id,tenant_id,kol_id,bd_user_id,start_time,end_time,create_time,update_time) VALUES(?,1,?,?,?,?,now(),now())",
        id,
        kol,
        bd,
        Timestamp.from(from),
        to == null ? null : Timestamp.from(to));
  }

  @Test
  void shouldUsePaymentIntervalAndTenantIsolation_whenLookingUpMappings() {
    mapping(1, 100, "90", PAID.minusSeconds(1), PAID.plusSeconds(1));
    mapping(2, 100, "91", PAID.plusSeconds(1), null);
    mapping(3, 100, "92", PAID.minusSeconds(2), PAID);
    TenantScope.runCompany(
        1,
        () -> {
          assertThat(mappings.findAt(10L, "90", PAID).kolId()).isEqualTo(100);
          assertThat(mappings.findAt(10L, "91", PAID)).isNull();
          assertThat(mappings.findAt(10L, "92", PAID)).isNull();
        });
    TenantScope.runCompany(2, () -> assertThat(mappings.findAt(10L, "90", PAID)).isNull());
  }

  @Test
  void shouldPersistIdempotentlyAndPreserveAttribution_whenMappingChanges() {
    mapping(1, 100, "90", PAID.minusSeconds(1), null);
    history(1, 100, 8L, PAID.minusSeconds(1), null);
    long id = save("1000", PAID, line("90", "1"));
    jdbc.update(
        "UPDATE crm_kol_item SET valid_to=? WHERE id=1", Timestamp.from(PAID.plusSeconds(1)));
    mapping(2, 200, "90", PAID.plusSeconds(1), null);
    save("1000", PAID, line("90", "1"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_order", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_order_item", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT kol_id FROM crm_order WHERE id=?", Long.class, id))
        .isEqualTo(100);
    assertThat(jdbc.queryForObject("SELECT bd_user_id FROM crm_order WHERE id=?", Long.class, id))
        .isEqualTo(8);
    assertThat(jdbc.queryForObject("SELECT buyer_hash FROM crm_order WHERE id=?", String.class, id))
        .hasSize(64)
        .doesNotContain("test-buyer");
  }

  @Test
  void shouldSeparateConflictPartialAndUnpaidOrders_whenIngesting() {
    mapping(1, 100, "90", PAID.minusSeconds(1), null);
    mapping(2, 200, "91", PAID.minusSeconds(1), null);
    long conflict = save("1001", PAID, line("90", "1"), line("91", "2"));
    long partial = save("1002", PAID, line("90", "1"), line("92", "2"));
    long unpaid = save("1003", null, line("90", "1"));
    assertThat(
            jdbc.queryForObject(
                "SELECT attribution_status FROM crm_order WHERE id=?", String.class, conflict))
        .isEqualTo("CONFLICT");
    assertThat(
            jdbc.queryForList(
                "SELECT attribution_type FROM crm_order_item WHERE order_id=?",
                Integer.class,
                conflict))
        .containsOnly(4);
    assertThat(jdbc.queryForObject("SELECT kol_id FROM crm_order WHERE id=?", Long.class, partial))
        .isEqualTo(100);
    assertThat(
            jdbc.queryForObject(
                "SELECT attribution_status FROM crm_order WHERE id=?", String.class, unpaid))
        .isEqualTo("PENDING");
  }

  @Test
  void shouldConvertFenAndUpsertRefunds_whenRefundArrivesBeforeOrder() {
    var detail = detail("1000", PAID, line("90", "1"));
    when(remote.refundList(anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.RefundListResponse(
                List.of(
                    new WeidianBusinessApi.RefundListItem(
                        "500", "1000", new BigDecimal("2.30"), PAID, null)),
                1,
                null));
    when(remote.refundDetail(anyLong(), any()))
        .thenReturn(
            new WeidianBusinessApi.RefundDetailResponse(
                PAID,
                PAID,
                "500",
                "1000",
                new BigDecimal("230"),
                json.readTree(
                    "{\"refundBasicInfo\":{\"status\":10,\"itemList\":[{\"orderDescId\":\"1\"}]}}")));
    when(remote.orderDetail(anyLong(), any())).thenReturn(detail);
    TenantScope.runCompany(
        1,
        () -> {
          assertThat(sync.synchronize(10)).isTrue();
          assertThat(sync.synchronize(10)).isTrue();
        });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM crm_refund", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT amount FROM crm_refund", BigDecimal.class))
        .isEqualByComparingTo("2.30");
    assertThat(jdbc.queryForObject("SELECT refund_amount FROM crm_order_item", BigDecimal.class))
        .isEqualByComparingTo("2.30");
  }

  @Test
  void shouldUpdateOnlyAffectedPaymentPeriod_whenHistoryIsInsertedLate() {
    mapping(1, 100, "90", PAID.minusSeconds(100), null);
    long older = save("1001", PAID.minusSeconds(20), line("90", "1"));
    long newer = save("1002", PAID, line("90", "1"));
    history(1, 100, 8L, PAID.minusSeconds(10), PAID.plusSeconds(10));
    correction.correct();
    assertThat(
            jdbc.queryForObject("SELECT bd_user_id FROM crm_order WHERE id=?", Long.class, older))
        .isNull();
    assertThat(
            jdbc.queryForObject("SELECT bd_user_id FROM crm_order WHERE id=?", Long.class, newer))
        .isEqualTo(8);
    assertThat(redis.opsForValue().get("order:bd-snapshot:checkpoint")).isNotBlank();
    TenantScope.runCompany(
        1,
        () -> {
          assertThat(ownership.bdAt(100L, PAID.plusSeconds(10))).isNull();
          assertThat(ownership.bdAt(100L, PAID)).isEqualTo(8);
        });
  }

  @Test
  void shouldAssignNonKolAndReattributeWithAudit_whenOperatorActs() throws Exception {
    long id = save("1000", PAID, line("90", "1"));
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":100,\"reason\":\"Test confirmation\"}"))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT attribution_type FROM crm_order_item", Integer.class))
        .isEqualTo(2);
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nonKol\":true,\"reason\":\"Not a creator order\"}"))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT attribution_status FROM crm_order", String.class))
        .isEqualTo("NON_KOL");
    mapping(1, 100, "90", PAID.minusSeconds(1), null);
    mvc.perform(
            post("/admin-api/order/orders/" + id + "/reattribution").header("X-Admin-Token", admin))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT attribution_type FROM crm_order_item", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM sys_oper_log WHERE module='ORDER' AND request_params LIKE '%Test confirmation%'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldRejectForeignKolInvalidReasonAndUnpaidManualAssignment_whenWriting() throws Exception {
    long id = save("1000", PAID, line("90", "1"));
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":300,\"reason\":\"Foreign\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":100,\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":100,\"nonKol\":true,\"reason\":\"Invalid target\"}"))
        .andExpect(status().isBadRequest());
    long unpaid = save("1001", null, line("90", "1"));
    mvc.perform(
            put("/admin-api/order/orders/" + unpaid + "/attribution")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":100,\"reason\":\"Unpaid\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void shouldScopeQueriesAndMutationsBySnapshot_whenStaffHasOwnScope() throws Exception {
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "order-test-bd",
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
                            List.of("order:view", "order:manage")))))
        .andExpect(status().isOk());
    long bd =
        jdbc.queryForObject("SELECT id FROM sys_user WHERE username='order-test-bd'", Long.class);
    String token = CompanySessions.login(mvc, json, "order-test-bd", PASSWORD);
    long owned = save("1000", PAID, line("90", "1"));
    long other = save("1001", PAID, line("90", "1"));
    jdbc.update("UPDATE crm_order SET bd_user_id=? WHERE id=?", bd, owned);
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(owned)));
    mvc.perform(
            post("/admin-api/order/orders/" + other + "/reattribution")
                .header("X-Admin-Token", token))
        .andExpect(status().isForbidden());
    jdbc.update("UPDATE crm_order SET tenant_id=2 WHERE id=?", other);
    mvc.perform(
            post("/admin-api/order/orders/" + other + "/reattribution")
                .header("X-Admin-Token", admin))
        .andExpect(status().isNotFound());
    String body =
        mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("buyer", "raw", "NEVER-STORE", "test-buyer");
  }

  @Test
  void shouldRequirePermissionsAndReturnAcceptedOrConflict_whenTriggeringSync() throws Exception {
    mvc.perform(get("/admin-api/order/orders")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/admin-api/order/syncs")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":20}"))
        .andExpect(status().isNotFound());
    var held = redisson.getLock("order:sync:10");
    held.lock();
    try {
      mvc.perform(
              post("/admin-api/order/syncs")
                  .header("X-Admin-Token", admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"shopId\":10}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.errorCode").value("ORDER_SYNC_RUNNING"));
    } finally {
      held.unlock();
    }
    mvc.perform(
            post("/admin-api/order/syncs")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":10}"))
        .andExpect(status().isAccepted());
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM crm_order_sync_state WHERE shop_id=10 AND last_success_at IS NOT NULL",
                            Integer.class))
                    .isEqualTo(1));
  }

  @Test
  void shouldFilterUnmatchedOrdersAndRefundTotals_whenQuerying() throws Exception {
    mapping(1, 100, "90", PAID.minusSeconds(1), null);
    long id = save("1000", PAID, line("90", "1"), line("91", "2"));
    mvc.perform(
            get("/admin-api/order/unmatched")
                .header("X-Admin-Token", admin)
                .param("shopId", "10")
                .param("payFrom", PAID.minusSeconds(1).toString())
                .param("payTo", PAID.plusSeconds(1).toString())
                .param("kolCode", "KOL0100")
                .param("orderStatus", "PAID")
                .param("attributionStatus", "UNMATCHED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(id)));
    mvc.perform(
            get("/admin-api/order/orders")
                .header("X-Admin-Token", admin)
                .param("kolCode", "KOL9999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", admin).param("shopId", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    mvc.perform(
            get("/admin-api/order/orders")
                .header("X-Admin-Token", admin)
                .param("payFrom", PAID.plusSeconds(1).toString())
                .param("payTo", PAID.toString()))
        .andExpect(status().isBadRequest());
    TenantScope.runCompany(
        1, () -> assertThat(authorization.authorizedShopIds()).containsExactly(10L));
    TenantScope.runCompany(2, () -> assertThat(authorization.authorizedShopIds()).isEmpty());
  }

  @Test
  void shouldLinkUniqueSkuAndCountOnlyCompletedRefunds_whenRefundHasNoSuborderId() {
    save("1000", PAID, line("90", "1"));
    var raw =
        json.readTree(
            "{\"refundBasicInfo\":{\"status\":10,\"itemList\":[{\"curShopItemId\":\"90\",\"curShopSkuId\":\"0\"}]}}");
    TenantScope.runCompany(
        1,
        () ->
            writer.saveRefund(
                10,
                new WeidianBusinessApi.RefundDetailResponse(
                    PAID, PAID, "500", "1000", new BigDecimal("230"), raw)));
    assertThat(jdbc.queryForObject("SELECT order_item_id FROM crm_refund", Long.class)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT refund_amount FROM crm_order_item", BigDecimal.class))
        .isEqualByComparingTo("2.30");
    var cancelled = json.readTree("{\"refundBasicInfo\":{\"status\":9,\"itemList\":[]}}");
    TenantScope.runCompany(
        1,
        () ->
            writer.saveRefund(
                10,
                new WeidianBusinessApi.RefundDetailResponse(
                    PAID, PAID, "500", "1000", new BigDecimal("230"), cancelled)));
    assertThat(jdbc.queryForObject("SELECT order_item_id FROM crm_refund", Long.class)).isNull();
    assertThat(jdbc.queryForObject("SELECT refund_amount FROM crm_order_item", BigDecimal.class))
        .isEqualByComparingTo("0");
  }

  @Test
  void shouldRequireManagePermissionForEveryMutation_whenStaffCanOnlyView() throws Exception {
    long id = save("1000", PAID, line("90", "1"));
    mvc.perform(
            post("/admin-api/system/users")
                .header("X-Admin-Token", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "username",
                            "order-view-only",
                            "password",
                            PASSWORD,
                            "nickname",
                            "Test viewer",
                            "role",
                            "STAFF",
                            "postTemplate",
                            "OPERATOR",
                            "permissions",
                            List.of("order:view")))))
        .andExpect(status().isOk());
    String token = CompanySessions.login(mvc, json, "order-view-only", PASSWORD);
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", token))
        .andExpect(status().isOk());
    mvc.perform(get("/admin-api/order/unmatched").header("X-Admin-Token", token))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin-api/order/syncs")
                .header("X-Admin-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":10}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin-api/order/orders/" + id + "/reattribution").header("X-Admin-Token", token))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/admin-api/order/orders/" + id + "/attribution")
                .header("X-Admin-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolId\":100,\"reason\":\"No permission\"}"))
        .andExpect(status().isForbidden());
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    mvc.perform(get("/admin-api/order/orders").header("X-Admin-Token", platform))
        .andExpect(status().isForbidden());
  }
}
