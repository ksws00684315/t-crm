package com.tcrm.admin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.CompanySessions.OpenedCompany;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Task 20 against real PostgreSQL: commit, rollback, company isolation and the read APIs. */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class NotificationIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final AtomicInteger KOL_IDS = new AtomicInteger(710000);
  private static final String ADMIN_TOKEN = "X-Admin-Token";

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
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;

  @Autowired
  NotificationIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      ApplicationEventPublisher events,
      TransactionTemplate tx) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.events = events;
    this.tx = tx;
  }

  @Test
  void shouldWriteAfterCommit_forTheCompanySuperAdmins() throws Exception {
    OpenedCompany company = open("payout");
    OpenedCompany other = open("elsewhere");
    long kolId = KOL_IDS.getAndIncrement();
    jdbc.update(
        "INSERT INTO crm_kol (id, tenant_id, kol_code, name, email, country, create_time, update_time) VALUES (?, ?, ?, 'Ada', ?, 'US', now(), now())",
        kolId,
        company.tenantId(),
        "KOL-AAA" + String.format("%03d", kolId),
        "ada" + kolId + "@example.com");
    publish(
        company.tenantId(),
        new BdTransferCancelledEvent(
            company.tenantId(),
            kolId,
            77L,
            "KOL-AAA" + String.format("%03d", kolId),
            "OWNERSHIP_CHANGED"));
    assertThat(count(company.tenantId())).isEqualTo(1);
    String params =
        jdbc.queryForObject(
            "SELECT params::text FROM crm_notification WHERE tenant_id = ? AND user_id = ?",
            String.class,
            company.tenantId(),
            company.userId());
    assertThat(params).contains("kolCode").contains("kolName").contains("Ada").doesNotContain("@");
    // ADR-022: the super administrator role is per company, so another company's administrator
    // (enabled, opened before the publish) receives nothing.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_notification WHERE user_id = ?",
                Long.class,
                other.userId()))
        .isZero();
  }

  @Test
  void shouldNotWrite_whenTheBusinessTransactionRollsBack() throws Exception {
    OpenedCompany company = open("rollback");
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      TenantScope.runCompany(
                          company.tenantId(),
                          () ->
                              TransactionalEvents.publish(
                                  events,
                                  new OrderSyncFailedEvent(company.tenantId(), 9L, "Shop A")));
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(company.tenantId())).isZero();
  }

  @Test
  void shouldHideAnotherCompanysMessages_andRejectAPlatformAdministrator() throws Exception {
    OpenedCompany company = open("isolation");
    OpenedCompany other = open("other");
    publish(company.tenantId(), new OrderSyncFailedEvent(company.tenantId(), 9L, "Shop A"));
    assertThat(count(company.tenantId())).isEqualTo(1);
    mvc.perform(get("/admin-api/notification/notifications").header(ADMIN_TOKEN, other.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("0"));
    String platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    mvc.perform(
            get("/admin-api/notification/notifications/unread-count").header(ADMIN_TOKEN, platform))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldReadAndMarkOnlyTheCallersMessages() throws Exception {
    OpenedCompany company = open("inbox");
    long staff =
        CompanySessions.createStaff(
            mvc, json, company.token(), PASSWORD, "st" + company.tenantId(), "BD", "OWN", null);
    String staffToken = CompanySessions.login(mvc, json, "st" + company.tenantId(), PASSWORD);
    publish(company.tenantId(), new OrderSyncFailedEvent(company.tenantId(), 9L, "Shop A"));
    mvc.perform(
            get("/admin-api/notification/notifications/unread-count")
                .header(ADMIN_TOKEN, company.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.count").value("1"));
    String body =
        mvc.perform(
                get("/admin-api/notification/notifications").header(ADMIN_TOKEN, company.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].params.shopName").value("Shop A"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(body).at("/data/items/0/id").asText();
    mvc.perform(
            put("/admin-api/notification/notifications/" + id + "/read")
                .header(ADMIN_TOKEN, staffToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
    mvc.perform(
            put("/admin-api/notification/notifications/" + id + "/read")
                .header(ADMIN_TOKEN, company.token()))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin-api/notification/notifications/" + id + "/read")
                .header(ADMIN_TOKEN, company.token()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/admin-api/notification/notifications/unread-count")
                .header(ADMIN_TOKEN, company.token()))
        .andExpect(jsonPath("$.data.count").value("0"));
    assertThat(staff).isPositive();
  }

  @Test
  void shouldWriteTheEventCompany_whenPublishedFromSystemScope() throws Exception {
    OpenedCompany company = open("system");
    TenantScope.runSystem(
        "scheduled order sync",
        () ->
            tx.executeWithoutResult(
                status ->
                    TransactionalEvents.publish(
                        events, new OrderSyncFailedEvent(company.tenantId(), 9L, "Shop A"))));
    assertThat(count(company.tenantId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM crm_notification WHERE biz_id = 9 AND tenant_id = ?",
                Long.class,
                company.tenantId()))
        .isEqualTo(company.tenantId());
  }

  private OpenedCompany open(String prefix) throws Exception {
    String code = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return CompanySessions.openCompany(mvc, json, PASSWORD, code, "a" + code);
  }

  private void publish(long tenantId, Object event) {
    tx.executeWithoutResult(
        status ->
            TenantScope.runCompany(tenantId, () -> TransactionalEvents.publish(events, event)));
  }

  private long count(long tenantId) {
    Long rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_notification WHERE tenant_id = ?", Long.class, tenantId);
    return rows == null ? 0L : rows;
  }
}
