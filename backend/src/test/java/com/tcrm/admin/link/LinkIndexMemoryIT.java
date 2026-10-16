package com.tcrm.admin.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.link.service.LinkIndexStore;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Measures the Redis memory of the two link index hash families at the contract scale (task 11
 * §4.4: the report quotes the measured memory of both families for two million mappings). The
 * design capacity is 100 KOLs × 20,000 mappings; every mapping occupies one field of {@code
 * link:{kolCode}} and one of {@code linkitem:{nn}} with the same JSON value. Values use the
 * design's value shape (Weidian target, §7.4); agent platform targets are longer by the length of
 * the template URL.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LinkIndexMemoryIT {
  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexMemoryIT.class);
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final int KOLS = 100;
  private static final int ITEMS_PER_KOL = 20_000;

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
    WeidianTestProperties.register(r);
  }

  private final StringRedisTemplate redis;
  private final LinkIndexStore store;

  @Autowired
  LinkIndexMemoryIT(StringRedisTemplate redis, LinkIndexStore store) {
    this.redis = redis;
    this.store = store;
  }

  @Test
  void shouldMeasureBothHashFamilies_atTwoMillionMappings() {
    Set<String> keys = redis.keys("link:*");
    keys.addAll(redis.keys("linkitem:*"));
    redis.delete(keys);
    long empty = usedMemory();

    for (int kol = 0; kol < KOLS; kol++) {
      String kolCode = String.format(Locale.ROOT, "KOL%04d", kol + 1);
      Map<String, String> linkFields = new LinkedHashMap<>();
      for (int i = 0; i < ITEMS_PER_KOL; i++) {
        String item = item(kol, i);
        linkFields.put(productCode(i), indexValue(kolItemId(kol, i), item));
      }
      store.putAll(kolCode, linkFields);
    }
    long afterKolHashes = usedMemory();

    for (int kol = 0; kol < KOLS; kol++) {
      Map<String, String> itemFields = new LinkedHashMap<>();
      for (int i = 0; i < ITEMS_PER_KOL; i++) {
        String item = item(kol, i);
        itemFields.put(item, indexValue(kolItemId(kol, i), item));
      }
      store.putItemFields(itemFields);
    }
    long afterItemHashes = usedMemory();

    LOG.info(
        "Link index memory at {} mappings: link:* {} MiB, linkitem:* {} MiB, total {} MiB"
            + " (used_memory; empty instance {} MiB)",
        (long) KOLS * ITEMS_PER_KOL,
        mib(afterKolHashes - empty),
        mib(afterItemHashes - afterKolHashes),
        mib(afterItemHashes - empty),
        mib(empty));

    assertThat(redis.opsForHash().size("link:KOL0001")).isEqualTo(ITEMS_PER_KOL);
    assertThat(redis.opsForHash().size("link:KOL0100")).isEqualTo(ITEMS_PER_KOL);
    // Two million IDs spread over 100 buckets by their last two digits: 20,000 each.
    assertThat(redis.opsForHash().size("linkitem:00")).isEqualTo(KOLS * ITEMS_PER_KOL / 100);
    assertThat(redis.opsForHash().size("linkitem:99")).isEqualTo(KOLS * ITEMS_PER_KOL / 100);
    assertThat(afterItemHashes).isGreaterThan(afterKolHashes);
  }

  private static String item(int kol, int i) {
    return String.valueOf(9_000_000L + (long) kol * ITEMS_PER_KOL + i);
  }

  private static String productCode(int i) {
    return "P" + String.format(Locale.ROOT, "%06d", i);
  }

  private static String kolItemId(int kol, int i) {
    return String.valueOf(1_930_000_000_000_000_000L + (long) kol * ITEMS_PER_KOL + i);
  }

  private static String indexValue(String kolItemId, String item) {
    return "{\"kolItemId\":\""
        + kolItemId
        + "\",\"targetUrl\":\"https://weidian.com/item.html?itemID="
        + item
        + "\",\"status\":1}";
  }

  private long usedMemory() {
    Properties info = redis.getConnectionFactory().getConnection().serverCommands().info("memory");
    return Long.parseLong(info.getProperty("used_memory", "0"));
  }

  private static String mib(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
  }
}
