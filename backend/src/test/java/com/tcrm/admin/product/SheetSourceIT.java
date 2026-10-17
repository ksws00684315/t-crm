package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.TestWait;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.integration.service.GoogleTestKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
 * ADR-021 end to end against PostgreSQL and Redis, with Google's token and Sheets endpoints served
 * by a local JDK stub: credential upload, source configuration, a read through the product import,
 * products that left the sheet, Google errors, and permissions.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SheetSourceIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String SHEET_ID = "1utim6wvKOCcQf1Qgvzba2Y7bQ79F2T5lqguJz-ODf1E";
  private static final AtomicReference<String> GRID = new AtomicReference<>();
  private static final AtomicInteger SHEETS_STATUS = new AtomicInteger(200);
  private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
  private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();
  private static final HttpServer GOOGLE = startGoogle();

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
    r.add("crm.google.token-uri", SheetSourceIT::tokenUri);
    r.add("crm.google.sheets-base-url", () -> base(""));
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private String admin;

  @Autowired
  SheetSourceIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() throws Exception {
    for (String table :
        List.of(
            "crm_sheet_source_item",
            "crm_sheet_source",
            "crm_google_credential",
            "crm_link_index_outbox",
            "crm_kol_item",
            "crm_product",
            "crm_shop",
            "crm_task_file",
            "crm_task",
            "sys_user_permission",
            "sys_oper_log")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    SHEETS_STATUS.set(200);
    GRID.set(
        grid(
            row(3, "7736025747", "Hoodie", "$60"),
            row(4, "7739078620", "Zip Up", "¥145 | $21.13")));
    admin = CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  @Test
  void shouldReadTheSourceIntoOriginalProducts_andListProductsThatLeftTheSheet() throws Exception {
    long shopId = createShop("1856156775");
    configureCredential();
    long sourceId = createSource(shopId);

    JsonNode first = read(sourceId);
    assertThat(first.path("rowCount").asInt()).isEqualTo(2);
    assertThat(awaitTask(first.path("taskId").asText()).path("status").asText())
        .isEqualTo("SUCCESS");
    assertThat(LAST_AUTH.get()).isEqualTo("Bearer ya29.stub");
    assertThat(LAST_QUERY.get()).contains("ranges=").contains("includeGridData=true");
    List<Map<String, Object>> products =
        jdbc.queryForList(
            "SELECT weidian_item_id, name, price, price_currency, status FROM crm_product"
                + " ORDER BY weidian_item_id");
    assertThat(products)
        .extracting(
            p -> p.get("weidian_item_id") + "|" + p.get("name") + "|" + p.get("price_currency"))
        .containsExactly("7736025747|Hoodie|USD", "7739078620|Zip Up|USD");
    assertThat(products.get(1).get("price")).isEqualTo(new BigDecimal("21.13"));

    // The second product leaves the sheet: it stays on sale and is reported, not delisted.
    GRID.set(grid(row(3, "7736025747", "Hoodie v2", "$61")));
    JsonNode second = read(sourceId);
    awaitTask(second.path("taskId").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM crm_product WHERE weidian_item_id = '7736025747'", String.class))
        .isEqualTo("Hoodie v2");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM crm_product WHERE weidian_item_id = '7739078620'",
                Integer.class))
        .isEqualTo(1);
    mvc.perform(
            get("/admin-api/product/sheet-sources/" + sourceId + "/missing-products")
                .header(TOKEN, admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].weidianItemId").value("7739078620"))
        .andExpect(jsonPath("$.data.items[0].productName").value("Zip Up"))
        .andExpect(jsonPath("$.data.items[0].shopName").value("Main shop"));

    // Back in the sheet: no longer reported.
    GRID.set(grid(row(3, "7736025747", "Hoodie v2", "$61"), row(9, "7739078620", "Zip Up", "$22")));
    awaitTask(read(sourceId).path("taskId").asText());
    mvc.perform(
            get("/admin-api/product/sheet-sources/" + sourceId + "/missing-products")
                .header(TOKEN, admin))
        .andExpect(jsonPath("$.data.total").value(0));
    mvc.perform(get("/admin-api/product/sheet-sources").header(TOKEN, admin))
        .andExpect(jsonPath("$.data[0].lastRowCount").value(2))
        .andExpect(jsonPath("$.data[0].lastError").doesNotExist())
        .andExpect(jsonPath("$.data[0].defaultShopName").value("Main shop"));
  }

  @Test
  void shouldRecordTheGoogleError_whenTheSheetIsNotShared() throws Exception {
    long sourceId = createSource(createShop("1856156775"));
    mvc.perform(
            post("/admin-api/product/sheet-sources/" + sourceId + "/reads").header(TOKEN, admin))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("GOOGLE_CREDENTIAL_MISSING"));

    configureCredential();
    SHEETS_STATUS.set(403);
    mvc.perform(
            post("/admin-api/product/sheet-sources/" + sourceId + "/reads").header(TOKEN, admin))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("GOOGLE_SHEET_FORBIDDEN"));

    assertThat(
            jdbc.queryForObject(
                "SELECT last_error || ':' || read_no FROM crm_sheet_source", String.class))
        .isEqualTo("GOOGLE_SHEET_FORBIDDEN:0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_task", Integer.class)).isZero();
  }

  @Test
  void shouldNeverReturnOrLogThePrivateKey() throws Exception {
    configureCredential();

    String body =
        mvc.perform(get("/admin-api/integration/google-credential").header(TOKEN, admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.clientEmail").value(GoogleTestKeys.EMAIL))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("PRIVATE KEY");
    assertThat(
            jdbc.queryForObject(
                "SELECT private_key_cipher FROM crm_google_credential", String.class))
        .startsWith("v1:")
        .doesNotContain("PRIVATE KEY");
    assertThat(
            jdbc.queryForList(
                "SELECT request_params FROM sys_oper_log WHERE module = 'Google'", String.class))
        .hasSize(1)
        .allSatisfy(params -> assertThat(params).doesNotContain("PRIVATE KEY"));
  }

  @Test
  void shouldLetOperatorsReadButOnlySuperAdministratorsConfigure() throws Exception {
    long sourceId = createSource(createShop("1856156775"));
    configureCredential();
    CompanySessions.createStaff(
        mvc,
        json,
        admin,
        PASSWORD,
        "operator1",
        "OPERATOR",
        "ALL",
        List.of("product:view", "product:edit"));
    CompanySessions.createStaff(
        mvc, json, admin, PASSWORD, "viewer1", "OPERATOR", "ALL", List.of("product:view"));
    String operator = CompanySessions.login(mvc, json, "operator1", PASSWORD);
    String viewer = CompanySessions.login(mvc, json, "viewer1", PASSWORD);

    mvc.perform(get("/admin-api/product/sheet-sources").header(TOKEN, viewer))
        .andExpect(status().isOk());
    mvc.perform(
            post("/admin-api/product/sheet-sources/" + sourceId + "/reads").header(TOKEN, viewer))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin-api/product/sheet-sources")
                .header(TOKEN, operator)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(source(null))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin-api/integration/google-credential").header(TOKEN, operator))
        .andExpect(status().isForbidden());
    String accepted =
        mvc.perform(
                post("/admin-api/product/sheet-sources/" + sourceId + "/reads")
                    .header(TOKEN, operator))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // Finish the import here so it cannot leak into the next test's data.
    awaitTask(json.readTree(accepted).at("/data/taskId").asText());
  }

  private JsonNode read(long sourceId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/product/sheet-sources/" + sourceId + "/reads")
                    .header(TOKEN, admin))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).path("data");
  }

  private void configureCredential() throws Exception {
    mvc.perform(
            put("/admin-api/integration/google-credential")
                .header(TOKEN, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("credentialJson", GoogleTestKeys.json(tokenUri())))))
        .andExpect(status().isOk());
  }

  private long createShop(String weidianShopId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/product/shops")
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("shopName", "Main shop", "weidianShopId", weidianShopId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/id").asLong();
  }

  private long createSource(long shopId) throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/product/sheet-sources")
                    .header(TOKEN, admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(source(shopId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/id").asLong();
  }

  private static Map<String, Object> source(Long shopId) {
    var body = new LinkedHashMap<String, Object>();
    body.put("sourceName", "Main");
    body.put("sheet", "https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit#gid=1");
    body.put("sheetTitle", "ARCHIVE style");
    body.put("headerRow", 2);
    body.put("linkColumn", "E");
    body.put("nameColumn", "B");
    body.put("priceColumn", "D");
    body.put("defaultShopId", shopId == null ? null : String.valueOf(shopId));
    body.put("priceCurrency", "USD");
    return body;
  }

  private JsonNode awaitTask(String taskId) throws Exception {
    JsonNode[] holder = new JsonNode[1];
    TestWait.until(
        java.time.Duration.ofSeconds(30),
        java.time.Duration.ofMillis(100),
        () -> {
          try {
            String body =
                mvc.perform(get("/admin-api/task/tasks/" + taskId).header(TOKEN, admin))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode data = json.readTree(body).path("data");
            String s = data.path("status").asText();
            if ("SUCCESS".equals(s) || "PARTIAL".equals(s) || "FAILED".equals(s)) {
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

  /** One sheet row: link in E, name in B, price in D. */
  private record Row(int row, String itemId, String name, String price) {}

  private static Row row(int row, String itemId, String name, String price) {
    return new Row(row, itemId, name, price);
  }

  /** Grid data for the requested ranges E3:E, B3:B, D3:D, in that order, starting at row 3. */
  private static String grid(Row... rows) {
    int last = 0;
    for (Row r : rows) {
      last = Math.max(last, r.row());
    }
    StringBuilder links = new StringBuilder();
    StringBuilder names = new StringBuilder();
    StringBuilder prices = new StringBuilder();
    for (int n = 3; n <= last; n++) {
      Row r = null;
      for (Row candidate : rows) {
        if (candidate.row() == n) {
          r = candidate;
        }
      }
      String sep = n == 3 ? "" : ",";
      if (r == null) {
        links.append(sep).append("{}");
        names.append(sep).append("{}");
        prices.append(sep).append("{}");
        continue;
      }
      links
          .append(sep)
          .append(
              "{\"values\":[{\"formattedValue\":\"LINK\",\"hyperlink\":"
                  + "\"https://item.kakobuy.com/item/details?url=https%3A%2F%2Fweidian.com"
                  + "%2Fitem.html%3FitemID%3D"
                  + r.itemId()
                  + "\"}]}");
      names
          .append(sep)
          .append("{\"values\":[{\"formattedValue\":\"")
          .append(r.name())
          .append("\"}]}");
      prices
          .append(sep)
          .append("{\"values\":[{\"formattedValue\":\"")
          .append(r.price())
          .append("\"}]}");
    }
    return "{\"sheets\":[{\"data\":["
        + "{\"startRow\":2,\"rowData\":["
        + links
        + "]},{\"startRow\":2,\"rowData\":["
        + names
        + "]},{\"startRow\":2,\"rowData\":["
        + prices
        + "]}]}]}";
  }

  private static String tokenUri() {
    return base("/token");
  }

  private static String base(String path) {
    return "http://127.0.0.1:" + GOOGLE.getAddress().getPort() + path;
  }

  private static HttpServer startGoogle() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            String form =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = form.contains("grant_type=urn") && form.contains("assertion=");
            byte[] out =
                (ok ? "{\"access_token\":\"ya29.stub\",\"expires_in\":3600}" : "{\"error\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 400, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
          });
      server.createContext(
          "/v4/spreadsheets/" + SHEET_ID,
          exchange -> {
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            LAST_QUERY.set(exchange.getRequestURI().getRawQuery());
            int code = SHEETS_STATUS.get();
            byte[] out =
                (code == 200 ? GRID.get() : "{\"error\":{\"code\":" + code + "}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
