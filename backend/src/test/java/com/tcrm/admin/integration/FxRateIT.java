package com.tcrm.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.api.FxRateDTO;
import com.tcrm.admin.integration.api.FxRates;
import com.tcrm.admin.integration.service.FxRateFetchJob;
import com.tcrm.admin.shared.TenantScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Task 14: the hourly ECB job stores each reference date once, served by a local JDK stub. */
@Tag("docker")
@Testcontainers
@SpringBootTest
class FxRateIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final AtomicReference<byte[]> FEED =
      new AtomicReference<>(fixture("ecb-2026-10-15.xml"));
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static final HttpServer ECB = startServer();

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
    r.add("crm.link.base-url", () -> "https://t.example.test");
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.test");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    r.add(
        "crm.fx.ecb-url",
        () -> "http://127.0.0.1:" + ECB.getAddress().getPort() + "/eurofxref-daily.xml");
    WeidianTestProperties.register(r);
  }

  private final JdbcTemplate jdbc;
  private final FxRateFetchJob job;
  private final FxRates rates;

  @Autowired
  FxRateIT(JdbcTemplate jdbc, FxRateFetchJob job, FxRates rates) {
    this.jdbc = jdbc;
    this.job = job;
    this.rates = rates;
  }

  @BeforeEach
  void resetTableAndFeed() {
    jdbc.update("DELETE FROM crm_fx_rate");
    FEED.set(fixture("ecb-2026-10-15.xml"));
    REQUESTS.set(0);
  }

  @Test
  void shouldStoreEachEcbDateOnce_andReturnTheNewest() throws InterruptedException {
    job.run();
    job.run();

    assertThat(REQUESTS).hasValue(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_fx_rate", Long.class)).isEqualTo(1);
    // 1.0812 ÷ 7.7123 = 0.140191641922... → 0.14019164
    assertThat(
            jdbc.queryForMap(
                "SELECT rate_date, base, quote, rate, source, create_by FROM crm_fx_rate"))
        .containsEntry("rate_date", Date.valueOf("2026-10-15"))
        .containsEntry("base", "CNY")
        .containsEntry("quote", "USD")
        .containsEntry("rate", new BigDecimal("0.14019164"))
        .containsEntry("source", "ECB")
        .containsEntry("create_by", null);

    FEED.set(fixture("ecb-2026-10-16.xml"));
    job.run();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_fx_rate", Long.class)).isEqualTo(2);
    // 1.0900 ÷ 7.8000 = 0.139743589743... → 0.13974359
    assertThat(rates.latest())
        .contains(new FxRateDTO(LocalDate.of(2026, 10, 16), new BigDecimal("0.13974359")));
  }

  @Test
  void shouldKeepExistingRows_whenTheFeedIsRejected() throws InterruptedException {
    job.run();
    FEED.set("<Envelope><Cube/></Envelope>".getBytes(StandardCharsets.UTF_8));

    job.run();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_fx_rate", Long.class)).isEqualTo(1);
    assertThat(rates.latest()).map(FxRateDTO::rateDate).contains(LocalDate.of(2026, 10, 15));
  }

  @Test
  void shouldReadTheRate_whenInsideACompanyScope() throws InterruptedException {
    job.run();

    // Platform-level table without tenant_id: no company predicate may be appended.
    var latest = TenantScope.callCompany(1L, rates::latest);

    assertThat(latest).map(FxRateDTO::rate).contains(new BigDecimal("0.14019164"));
  }

  @Test
  void shouldReturnEmpty_whenNoRateIsStored() {
    assertThat(rates.latest()).isEmpty();
  }

  @Test
  void shouldCommentTheTableAndEveryColumn_whenMigrated() {
    assertThat(
            jdbc.queryForObject(
                "SELECT obj_description('crm_fx_rate'::regclass, 'pg_class')", String.class))
        .contains("§3.4");
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns c
                WHERE c.table_schema = 'public' AND c.table_name = 'crm_fx_rate'
                  AND col_description('crm_fx_rate'::regclass, c.ordinal_position) IS NULL
                """,
                Long.class))
        .isZero();
  }

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/eurofxref-daily.xml",
          exchange -> {
            REQUESTS.incrementAndGet();
            byte[] body = FEED.get();
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] fixture(String name) {
    try (InputStream in = FxRateIT.class.getResourceAsStream("/fx/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
