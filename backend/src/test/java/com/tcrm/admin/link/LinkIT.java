package com.tcrm.admin.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.idev.excel.FastExcel;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickLogDO;
import com.tcrm.admin.link.service.ClickConsumer;
import com.tcrm.admin.link.service.ClickEvent;
import com.tcrm.admin.link.service.ClickMessage;
import com.tcrm.admin.link.service.ClickPartitionJob;
import com.tcrm.admin.link.service.ClickPartitions;
import com.tcrm.admin.link.service.ClickStream;
import com.tcrm.admin.link.service.LinkIndexService;
import com.tcrm.admin.link.service.LinkIndexStore;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    r.add("crm.kol-portal.base-url", () -> "https://kol.example.com");
    r.add("crm.link.click-consumer.enabled", () -> "false");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final LinkIndexService index;
  private final LinkIndexSyncJob sync;
  private final LinkIndexStore store;
  private final ApplicationEventPublisher events;
  private final PlatformTransactionManager transactions;
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
      LinkIndexSyncJob sync,
      LinkIndexStore store,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactions,
      ClickStream clickStream,
      ClickConsumer consumer,
      ClickPartitionJob partitions,
      ClickLogMapper clickLogs) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.index = index;
    this.sync = sync;
    this.store = store;
    this.events = events;
    this.transactions = transactions;
    this.clickStream = clickStream;
    this.consumer = consumer;
    this.partitions = partitions;
    this.clickLogs = clickLogs;
  }

  private String adminToken;

  @BeforeEach
  void prepareIndependentData() throws Exception {
    jdbc.update("DELETE FROM crm_link_index_outbox");
    jdbc.update("DELETE FROM crm_click_log");
    jdbc.update("DELETE FROM crm_kol_item");
    store.setBeforeRename(() -> {});
    store.failNextWrite(null);
    sync.setScheduled(false);
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_link_template");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    Set<String> keys = redis.keys("link:*");
    keys.addAll(redis.keys("linkitem:*"));
    keys.addAll(redis.keys("click:last:*"));
    keys.add("stream:click");
    redis.delete(keys);
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  @Test
  void shouldWriteIndexInRedirectFormat_andFollowStatusChanges() throws Exception {
    long shopId = createShop("店A", "1001");
    String kolCode = createKol("Alice", "alice@example.com");
    String productCode = importProduct(shopId, "2001", "鞋");
    importMapping("1001", "9001", kolCode, productCode);
    syncIndex();
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
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/kol/kols/" + kolId + "/status", 1);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");
    changeStatus("/admin-api/product/products/" + productId + "/status", 0);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/product/products/" + productId + "/status", 1);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");
    changeStatus("/admin-api/product/shops/" + shopId + "/status", 0);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    changeStatus("/admin-api/product/shops/" + shopId + "/status", 1);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");

    importMapping("1001", "9002", kolCode, productCode);
    syncIndex();
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
    syncIndex();
    String good1 = field(kolCode, p1);
    String good2 = field(kolCode, p2);

    redis.opsForHash().delete("link:" + kolCode, p1);
    redis.opsForHash().put("link:" + kolCode, p2, "{\"kolItemId\":\"1\"}");
    redis.opsForHash().put("link:" + kolCode, "P999999", "stale");
    redis.opsForHash().put("link:KOL99999", "P1", "orphan");

    TenantScope.runSystem("test rebuild of every company", index::rebuildAll);

    assertThat(field(kolCode, p1)).isEqualTo(good1);
    assertThat(field(kolCode, p2)).isEqualTo(good2);
    assertThat(redis.opsForHash().keys("link:" + kolCode)).containsExactlyInAnyOrder(p1, p2);
    assertThat(redis.hasKey("link:KOL99999")).isFalse();
    assertThat(redis.keys("link-rebuild:*")).isEmpty();

    mvc.perform(post("/admin-api/link/index-rebuilds").header(TOKEN, adminToken))
        .andExpect(status().isAccepted());
  }

  /**
   * Rebuild reads the old mapping, a newer mapping commits while the staging key is held, and the
   * rename must not discard that newer mapping. The sync waits on the KOL lock until the rename
   * finishes, then re-reads the database.
   */
  @Test
  void shouldKeepMappingCommittedDuringRebuild() throws Exception {
    long shopId = createShop("店R", "1008");
    String kolCode = createKol("Rae", "rae@example.com");
    String productCode = importProduct(shopId, "2801", "表");
    importMapping("1008", "9801", kolCode, productCode);
    syncIndex();
    long oldItemId = currentKolItemId(kolCode, productCode);

    CountDownLatch snapshotTaken = new CountDownLatch(1);
    CountDownLatch releaseRename = new CountDownLatch(1);
    store.setBeforeRename(
        () -> {
          snapshotTaken.countDown();
          try {
            if (!releaseRename.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("rebuild was not released");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
    AtomicReference<Throwable> rebuildError = new AtomicReference<>();
    Thread rebuild =
        new Thread(
            () -> {
              try {
                TenantScope.runSystem("test rebuild of every company", index::rebuildAll);
              } catch (Throwable error) {
                rebuildError.set(error);
              }
            },
            "link-it-rebuild");
    rebuild.start();
    assertThat(snapshotTaken.await(15, TimeUnit.SECONDS)).isTrue();

    importMapping("1008", "9802", kolCode, productCode);
    long newItemId = currentKolItemId(kolCode, productCode);
    assertThat(newItemId).isNotEqualTo(oldItemId);
    AtomicReference<Throwable> syncError = new AtomicReference<>();
    Thread syncThread =
        new Thread(
            () -> {
              try {
                sync.drain();
              } catch (Throwable error) {
                syncError.set(error);
              }
            },
            "link-it-sync");
    syncThread.start();

    boolean[] overlappedHolder = {false};
    String newItem = "\"kolItemId\":\"" + newItemId + "\"";
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline && !overlappedHolder[0]) {
      String live = field(kolCode, productCode);
      if (live != null && live.contains(newItem)) {
        overlappedHolder[0] = true;
        break;
      }
      if (waitingForLock(syncThread)) {
        overlappedHolder[0] = true;
        break;
      }
      // Poll interval while waiting for rename/index overlap.
      Thread.sleep(25);
    }
    releaseRename.countDown();
    assertThat(rebuild.join(Duration.ofSeconds(20))).isTrue();
    assertThat(syncThread.join(Duration.ofSeconds(20))).isTrue();
    assertThat(rebuildError.get()).isNull();
    assertThat(syncError.get()).isNull();
    assertThat(overlappedHolder[0]).isTrue();
    assertThat(field(kolCode, productCode)).contains(newItem).contains("itemID=9802");
    assertThat(outboxCount()).isZero();
  }

  @Test
  void shouldWriteBothHashesWithTemplateTarget_andFollowTargetInputChanges() throws Exception {
    long shopId = createShop("店T", "1030");
    long templateId =
        createTemplate(
            "kakobuy",
            "https://item.kakobuy.com/item/details?url=x&affcode={INVITE_CODE}&item={PRODUCT_ID}");
    String kolCode = createKolWithTemplate("Tina", "tina@example.com", templateId, "aff-01", true);
    String productCode = importProduct(shopId, "2301", "裙");
    importMapping("1030", "9301", kolCode, productCode);
    syncIndex();

    String expected =
        "{\"kolItemId\":\""
            + currentKolItemId(kolCode, productCode)
            + "\",\"targetUrl\":\"https://item.kakobuy.com/item/details?url=x&affcode=aff-01&item=9301\",\"status\":1}";
    assertThat(field(kolCode, productCode)).isEqualTo(expected);
    assertThat(itemField("9301")).isEqualTo(expected);

    // A new invite code changes every target URL of the KOL; both hashes follow within seconds
    // through the outbox (ADR-013).
    editKol(kolCode, templateId, "aff-02");
    assertThat(outboxCount()).isEqualTo(1);
    syncIndex();
    String newTarget = expected.replace("aff-01", "aff-02");
    assertThat(field(kolCode, productCode)).isEqualTo(newTarget);
    assertThat(itemField("9301")).isEqualTo(newTarget);

    // A disabled template falls every KOL of it back to the Weidian page.
    mvc.perform(
            put("/admin-api/link/templates/" + templateId + "/status")
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    assertThat(outboxCount()).isEqualTo(1);
    syncIndex();
    String weidian =
        "{\"kolItemId\":\""
            + currentKolItemId(kolCode, productCode)
            + "\",\"targetUrl\":\"https://weidian.com/item.html?itemID=9301\",\"status\":1}";
    assertThat(field(kolCode, productCode)).isEqualTo(weidian);
    assertThat(itemField("9301")).isEqualTo(weidian);

    // The link list shows the same computed target.
    mvc.perform(get("/admin-api/link/links").param("kolCode", kolCode).header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.items[0].targetUrl")
                .value("https://weidian.com/item.html?itemID=9301"));
  }

  @Test
  void shouldDropItemField_whenMappingIsReplacedOrClosed() throws Exception {
    long shopId = createShop("店U", "1031");
    String kolCode = createKol("Uma", "uma@example.com");
    String productCode = importProduct(shopId, "2401", "袜");
    importMapping("1031", "9401", kolCode, productCode);
    syncIndex();
    assertThat(itemField("9401")).isNotNull();

    // Replacing the mapping moves the item field to the new exclusive item.
    importMapping("1031", "9402", kolCode, productCode);
    syncIndex();
    assertThat(itemField("9401")).isNull();
    assertThat(itemField("9402")).contains("itemID=9402");
    assertThat(redis.opsForHash().keys("link:" + kolCode)).containsExactly(productCode);
  }

  @Test
  void shouldRegisterEveryKolOfTheTemplate_whenItsUrlChanges() throws Exception {
    long shopId = createShop("店V", "1032");
    long templateId =
        createTemplate("cnfans", "https://cnfans.com/product/?shop_type=weidian&id={PRODUCT_ID}");
    String one = createKolWithTemplate("Vera", "vera@example.com", templateId, null, true);
    String two = createKolWithTemplate("Vic", "vic@example.com", templateId, null, true);
    String p1 = importProduct(shopId, "2501", "鞋");
    String p2 = importProduct(shopId, "2502", "靴");
    importMapping("1032", "9501", one, p1);
    importMapping("1032", "9502", two, p2);
    syncIndex();
    assertThat(field(one, p1)).contains("cnfans.com/product/?shop_type=weidian&id=9501");
    assertThat(field(two, p2)).contains("cnfans.com/product/?shop_type=weidian&id=9502");

    mvc.perform(
            put("/admin-api/link/templates/" + templateId)
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateName\":\"cnfans\",\"templateUrl\":"
                        + "\"https://cnfans.com/purchase/?shop_type=weidian&id={PRODUCT_ID}\","
                        + "\"sortOrder\":0}"))
        .andExpect(status().isOk());

    assertThat(outboxCount()).isEqualTo(2);
    syncIndex();
    assertThat(field(one, p1)).contains("cnfans.com/purchase/?shop_type=weidian&id=9501");
    assertThat(field(two, p2)).contains("cnfans.com/purchase/?shop_type=weidian&id=9502");
    assertThat(itemField("9501")).contains("cnfans.com/purchase");
    assertThat(itemField("9502")).contains("cnfans.com/purchase");
  }

  @Test
  void shouldRebuildItemBuckets_deletingOrphansButKeepingLiveMappings() throws Exception {
    long shopId = createShop("店W", "1033");
    String kolCode = createKol("Wes", "wes@example.com");
    String productCode = importProduct(shopId, "2601", "带");
    importMapping("1033", "9601", kolCode, productCode);
    syncIndex();
    String good = itemField("9601");
    // Orphans: an unknown item and a stale field of the KOL.
    redis.opsForHash().put("linkitem:01", "7701", "stale");
    redis.opsForHash().put("linkitem:99", "999999", "stale");

    TenantScope.runSystem("test rebuild of every company", index::rebuildAll);

    assertThat(itemField("9601")).isEqualTo(good);
    assertThat(redis.opsForHash().hasKey("linkitem:01", "7701")).isFalse();
    assertThat(redis.opsForHash().hasKey("linkitem:99", "999999")).isFalse();
  }

  @Test
  void shouldNotDeleteItemField_ofMappingCommittedDuringRebuild() throws Exception {
    long shopId = createShop("店X", "1034");
    String kolCode = createKol("Xiu", "xiu@example.com");
    String productCode = importProduct(shopId, "2701", "针");
    importMapping("1034", "9701", kolCode, productCode);
    syncIndex();

    CountDownLatch snapshotTaken = new CountDownLatch(1);
    CountDownLatch releaseRename = new CountDownLatch(1);
    store.setBeforeRename(
        () -> {
          snapshotTaken.countDown();
          try {
            if (!releaseRename.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("rebuild was not released");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
    AtomicReference<Throwable> rebuildError = new AtomicReference<>();
    Thread rebuild =
        new Thread(
            () -> {
              try {
                TenantScope.runSystem("test rebuild of every company", index::rebuildAll);
              } catch (Throwable error) {
                rebuildError.set(error);
              }
            },
            "link-it-rebuild");
    rebuild.start();
    assertThat(snapshotTaken.await(15, TimeUnit.SECONDS)).isTrue();

    // A mapping committed while the rebuild holds its snapshot: its item field must survive the
    // orphan sweep thanks to the database re-check.
    importMapping("1034", "9702", kolCode, productCode);
    releaseRename.countDown();
    assertThat(rebuild.join(Duration.ofSeconds(30))).isTrue();
    assertThat(rebuildError.get()).isNull();
    syncIndex();
    assertThat(itemField("9701")).isNull();
    assertThat(itemField("9702")).contains("itemID=9702");
    assertThat(field(kolCode, productCode)).contains("itemID=9702");
    assertThat(outboxCount()).isZero();
  }

  @Test
  void shouldHideAndRejectOtherCompaniesTemplates() throws Exception {
    var other = CompanySessions.openCompany(mvc, json, PASSWORD, "tpl-co", "tpl-admin");
    MvcResult created =
        mvc.perform(
                post("/admin-api/link/templates")
                    .header(TOKEN, other.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"templateCode\":\"secret\",\"templateName\":\"secret\","
                            + "\"templateUrl\":\"https://secret.example/i/{PRODUCT_ID}\","
                            + "\"sortOrder\":0}"))
            .andExpect(status().isOk())
            .andReturn();
    long otherTemplateId =
        Long.parseLong(
            json.readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asText());

    // Invisible in the list of the default company...
    mvc.perform(get("/admin-api/link/templates").header(TOKEN, adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.templateCode == 'secret')]").isEmpty());

    // ...and unusable for the default company's KOLs.
    mvc.perform(
            post("/admin-api/kol/kols")
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Zoe\",\"email\":\"zoe@example.com\",\"country\":\"US\","
                        + "\"linkTemplateId\":\""
                        + otherTemplateId
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("LINK_TEMPLATE_INVALID"));
  }

  @Test
  void shouldRetryOutboxRow_whenRedisWriteFails_andApplyItAfterRecovery() throws Exception {
    long shopId = createShop("店F", "1009");
    String kolCode = createKol("Fay", "fay@example.com");
    String productCode = importProduct(shopId, "2901", "戒");
    store.failNextWrite(new IllegalStateException("redis down"));
    importMapping("1009", "9901", kolCode, productCode);
    Instant before = Instant.now();
    sync.drain();
    Instant after = Instant.now();

    assertThat(outboxCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT attempts FROM crm_link_index_outbox", Integer.class))
        .isEqualTo(1);
    Instant nextAttempt =
        jdbc.queryForObject(
            "SELECT next_attempt_at FROM crm_link_index_outbox",
            (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant());
    assertThat(nextAttempt).isAfterOrEqualTo(before.plusSeconds(10).minusMillis(50));
    assertThat(nextAttempt).isBeforeOrEqualTo(after.plusSeconds(10).plusSeconds(1));
    assertThat(jdbc.queryForObject("SELECT last_error FROM crm_link_index_outbox", String.class))
        .contains("IllegalStateException")
        .contains("redis down");
    assertThat(field(kolCode, productCode)).isNull();

    sync.drain();
    assertThat(outboxCount()).isEqualTo(1);
    assertThat(field(kolCode, productCode)).isNull();

    // TIMESTAMPTZ(3) rounds now() to the millisecond, which can land up to 0.5 ms after the clock
    // reading drain() uses for "due"; move the row clearly into the past instead.
    jdbc.update("UPDATE crm_link_index_outbox SET next_attempt_at = now() - INTERVAL '1 second'");
    sync.drain();
    assertThat(outboxCount()).isZero();
    assertThat(field(kolCode, productCode)).contains("\"status\":1").contains("itemID=9901");
  }

  @Test
  void shouldNotInsertOutboxRow_whenBusinessTransactionRollsBack() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              TransactionalEvents.publish(events, new KolStatusChangedEvent(1L));
              status.setRollbackOnly();
            });
    assertThat(outboxCount()).isZero();
  }

  @Test
  void shouldDisableShopWithoutWritingRedisUntilTheWorkerRuns() throws Exception {
    long shopId = createShop("店C", "1010");
    String kolCode = createKol("Cara", "cara@example.com");
    String productCode = importProduct(shopId, "2010", "链");
    importMapping("1010", "9010", kolCode, productCode);
    syncIndex();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");

    changeStatus("/admin-api/product/shops/" + shopId + "/status", 0);

    assertThat(field(kolCode, productCode)).endsWith("\"status\":1}");
    assertThat(outboxCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT scope FROM crm_link_index_outbox", String.class))
        .isEqualTo("SHOP");
    sync.drain();
    assertThat(field(kolCode, productCode)).endsWith("\"status\":0}");
    assertThat(outboxCount()).isZero();
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
  void shouldMarkDupFromTheClickLog_whenTheRedisKeyIsGone() throws Exception {
    long shopId = createShop("店G", "1006");
    String kolCode = createKol("Gina", "gina@example.com");
    String productCode = importProduct(shopId, "2401", "帽");
    importMapping("1006", "9401", kolCode, productCode);
    long kolItemId = currentKolItemId(kolCode, productCode);
    clickStream.ensureGroup();
    long t0 = System.currentTimeMillis() - 30_000;

    append(kolItemId, t0, HASH_A, "Mozilla/5.0");
    assertThat(consumer.pollOnce()).isEqualTo(1);

    redis.delete(redis.keys("click:last:*"));
    append(kolItemId, t0 + 3_000, HASH_A, "Mozilla/5.0");
    assertThat(consumer.pollOnce()).isEqualTo(1);

    assertThat(jdbc.queryForList("SELECT is_dup FROM crm_click_log ORDER BY click_time, id"))
        .extracting(row -> row.get("is_dup"))
        .containsExactly(false, true);
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
      row.setTenantId(1L);
      rows.add(row);
    }
    assertThat(clickLogs.insertIgnore(rows)).isZero();
  }

  private String append(long kolItemId, long ts, String ipHash, String ua) {
    Map<String, String> event = new HashMap<>();
    event.put("kolItemId", Long.toString(kolItemId));
    event.put("kolCode", "KOL-AAA001");
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

  private void syncIndex() {
    sync.drain();
  }

  private long outboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM crm_link_index_outbox", Long.class);
  }

  private static boolean waitingForLock(Thread thread) {
    Thread.State state = thread.getState();
    if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
      return false;
    }
    return Arrays.stream(thread.getStackTrace())
        .anyMatch(frame -> frame.getClassName().contains("redisson"));
  }

  private String field(String kolCode, String productCode) {
    return (String) redis.opsForHash().get("link:" + kolCode, productCode);
  }

  /** Field of {@code HASH linkitem:{last two digits}} keyed by the exclusive item ID. */
  private String itemField(String weidianItemId) {
    String suffix = weidianItemId.substring(weidianItemId.length() - 2);
    return (String) redis.opsForHash().get("linkitem:" + suffix, weidianItemId);
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
                        "{\"shopName\":\""
                            + name
                            + "\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\""
                            + weidianId
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
  }

  private String createKol(String name, String email) throws Exception {
    return createKolWithTemplate(name, email, null, null, false);
  }

  /** KOL with the agent platform profile fields (task 11 §4.3); country omitted when minimal. */
  private String createKolWithTemplate(
      String name, String email, Long templateId, String inviteCode, boolean fullProfile)
      throws Exception {
    StringBuilder body = new StringBuilder();
    body.append("{\"name\":\"").append(name).append("\",\"email\":\"").append(email).append('"');
    if (fullProfile) {
      body.append(",\"country\":\"US\",\"socialAccounts\":[]")
          .append(",\"whatsapp\":\"+12345678\"")
          .append(",\"sheetUrl\":\"https://docs.google.com/spreadsheets/d/1/edit\"");
    }
    if (templateId != null) {
      body.append(",\"linkTemplateId\":\"").append(templateId).append('"');
    }
    if (inviteCode != null) {
      body.append(",\"inviteCode\":\"").append(inviteCode).append('"');
    }
    body.append('}');
    MvcResult result =
        mvc.perform(
                post("/admin-api/kol/kols")
                    .header(TOKEN, adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("kolCode")
        .asText();
  }

  /**
   * Edits the KOL's link target inputs through promotion config (task 28 §3.2): ordinary edit no
   * longer accepts invite code / jump template.
   */
  private void editKol(String kolCode, Long templateId, String inviteCode) throws Exception {
    long kolId = id("SELECT id FROM crm_kol WHERE kol_code = ?", kolCode);
    mvc.perform(
            post("/admin-api/kol/kols/" + kolId + "/review")
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"LinkIT\"}"))
        .andExpect(
            result -> {
              int s = result.getResponse().getStatus();
              if (s != 200 && s != 409) {
                throw new AssertionError("review failed: " + s);
              }
            });
    StringBuilder body = new StringBuilder();
    body.append(
        "{\"defaultRate\":\"0.1000\",\"sheetUrl\":\"https://docs.google.com/spreadsheets/d/1/edit\",\"remark\":\"integration test\"");
    if (templateId != null) {
      body.append(",\"platforms\":[{\"templateId\":\"")
          .append(templateId)
          .append("\",\"address\":\"https://landing.example/\"}]");
      body.append(",\"jumpTemplateId\":").append(templateId);
    } else {
      body.append(",\"platforms\":[]");
      body.append(",\"jumpTemplateId\":null");
    }
    if (inviteCode != null) {
      body.append(",\"inviteCode\":\"").append(inviteCode).append('"');
    } else {
      body.append(",\"inviteCode\":null");
    }
    body.append('}');
    mvc.perform(
            put("/admin-api/kol/kols/" + kolId + "/promotion-config")
                .header(TOKEN, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk());
  }

  private long createTemplate(String code, String url) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/admin-api/link/templates")
                    .header(TOKEN, adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"templateCode\":\""
                            + code
                            + "\",\"templateName\":\""
                            + code
                            + "\",\"templateUrl\":\""
                            + url
                            + "\",\"sortOrder\":0}"))
            .andExpect(status().isOk())
            .andReturn();
    return Long.parseLong(
        json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
  }

  private String importProduct(long shopId, String itemId, String name) throws Exception {
    String weidianShopId =
        jdbc.queryForObject(
            "SELECT weidian_shop_id FROM crm_shop WHERE id = ?", String.class, shopId);
    byte[] workbook =
        workbook(
            com.tcrm.admin.TestTemplates.render(ProductImportColumn.headerKeys()),
            List.of(List.of("", weidianShopId, itemId, name, "", "", "10.00", "")));
    awaitTask(submit("/admin-api/product/product-imports", workbook));
    return jdbc.queryForObject(
        "SELECT product_code FROM crm_product WHERE weidian_item_id = ?", String.class, itemId);
  }

  private void importMapping(String shop, String item, String kol, String product)
      throws Exception {
    byte[] workbook =
        workbook(
            com.tcrm.admin.TestTemplates.render(MappingImportColumn.headerKeys()),
            List.of(List.of(shop, item, kol, product)));
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
    JsonNode[] holder = new JsonNode[1];
    TestWait.until(
        java.time.Duration.ofSeconds(30),
        java.time.Duration.ofMillis(100),
        () -> {
          try {
            MvcResult result =
                mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, adminToken))
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

  private static byte[] workbook(List<String> headers, List<List<String>> rows) {
    List<List<String>> head = headers.stream().map(List::of).toList();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FastExcel.write(out).head(head).sheet("Sheet1").doWrite(rows);
    return out.toByteArray();
  }
}
