package com.tcrm.admin.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.service.KolWriter;
import com.tcrm.admin.link.service.LinkIndexSyncJob;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.TenantScope;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

/**
 * Task 26 against real PostgreSQL and Redis: the upload → preview → confirm flow, permission and
 * company isolation, preview expiry, the unchanged import limit, the KOL portal item list, and the
 * link index refresh for mappings without an original product.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PromoSheetIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String KOL_TOKEN = "X-Kol-Token";

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
    // The upload sub-domain (task 26 review) is a different origin from the console, so the
    // console's origin must be whitelisted for the cross-origin upload to pass CORS.
    r.add("crm.cors.allowed-origins", () -> "https://admin.example.com");
    WeidianTestProperties.register(r);
  }

  private final MockMvc mvc;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final LinkIndexSyncJob sync;
  private final KolWriter kolWriter;
  private final KolAccountStore accounts;
  private final BCryptPasswordEncoder passwords;
  private final KolItemApi items;

  @Autowired
  PromoSheetIT(
      MockMvc mvc,
      JdbcTemplate jdbc,
      ObjectMapper json,
      StringRedisTemplate redis,
      LinkIndexSyncJob sync,
      KolWriter kolWriter,
      KolAccountStore accounts,
      BCryptPasswordEncoder passwords,
      KolItemApi items) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.sync = sync;
    this.kolWriter = kolWriter;
    this.accounts = accounts;
    this.passwords = passwords;
    this.items = items;
  }

  private String adminToken;

  @BeforeEach
  void prepareIndependentData() throws Exception {
    sync.setScheduled(false);
    jdbc.update("DELETE FROM crm_link_index_outbox");
    jdbc.update("DELETE FROM crm_kol_item");
    jdbc.update("DELETE FROM crm_product");
    jdbc.update("DELETE FROM crm_shop");
    jdbc.update("DELETE FROM crm_kol_bd_history");
    jdbc.update("DELETE FROM crm_kol");
    jdbc.update("DELETE FROM crm_task_file");
    jdbc.update("DELETE FROM crm_task");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    deleteRedis("kol:promo-sheet:*");
    deleteRedis("link*");
    adminToken = CompanySessions.companyAdmin(mvc, json, PASSWORD);
  }

  private void deleteRedis(String pattern) {
    var keys = redis.keys(pattern);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  /** A promo sheet shaped like the customer sample: header on row 2, links in column E. */
  private byte[] sampleSheet() throws Exception {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("ARCHIVE style");
      Row head = sheet.createRow(1);
      head.createCell(0).setCellValue("PHOTO");
      head.createCell(1).setCellValue("NAME");
      head.createCell(2).setCellValue("CW");
      head.createCell(3).setCellValue("PRICE (USD)");
      head.createCell(4).setCellValue("LINK");
      linkRow(sheet, 2, "Silver Ring", kakobuy("111", "1111111111"));
      linkRow(sheet, 3, "Silver Ring (copy)", kakobuy("111", "1111111111"));
      linkRow(sheet, 4, "Golden Ring", kakobuy("111", "3333333333"));
      linkRow(sheet, 5, "Theirs", kakobuy("111", "2222222222"));
      linkRow(sheet, 6, null, "https://item.taobao.com/item.htm?id=9");
      linkRow(sheet, 7, null, "https://discord.com/invite/promo");
      linkRow(sheet, 8, null, kakobuy("999", "4444444444"));
      Row jump = sheet.createRow(9);
      jump.createCell(1).setCellValue("RETURN");
      CreationHelper helper = book.getCreationHelper();
      Hyperlink back = helper.createHyperlink(HyperlinkType.DOCUMENT);
      back.setAddress("'ARCHIVE style'!A1");
      jump.createCell(4).setHyperlink(back);
      jump.createCell(4).setCellValue("RETURN PAGE");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      book.write(out);
      return out.toByteArray();
    }
  }

  private static String kakobuy(String shop, String item) {
    return "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop"
        + shop
        + ".v.weidian.com%2Fitem.html%3FitemID%3D"
        + item;
  }

  private static void linkRow(Sheet sheet, int index, String name, String url) {
    Row row = sheet.createRow(index);
    if (name != null) {
      row.createCell(1).setCellValue(name);
    }
    row.createCell(4).setCellValue(url);
  }

  /** Shop 111 with one original product; KOL A owns 555…, KOL B owns 222…. */
  private long[] seed() {
    jdbc.update(
        "INSERT INTO crm_shop (id, tenant_id, shop_code, shop_name, weidian_shop_id, status, is_deleted,"
            + " create_time, update_time)"
            + " VALUES (11, 1, 'SHOP-900011', 'Main Shop', '111', 1, false, now(), now())");
    jdbc.update(
        "INSERT INTO crm_product (id, tenant_id, product_code, shop_id, weidian_item_id, name,"
            + " image_url, price, status, is_deleted, create_time, update_time)"
            + " VALUES (99, 1, 'P000001', 11, '1111111111', 'Original Silver Ring',"
            + " 'https://img.example.com/ring.png', 0, 1, false, now(), now())");
    long kolA = insertKol("a@example.com", "KOL-AAA001");
    long kolB = insertKol("b@example.com", "KOL-BBB002");
    insertMapping(kolB, null, "2222222222", "IMPORT");
    insertMapping(kolA, null, "5555555555", "IMPORT");
    return new long[] {kolA, kolB};
  }

  private long insertKol(String email, String code) {
    KolDO kol = new KolDO();
    kol.setName("Kol " + code);
    kol.setEmail(email);
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setStatus(1);
    TenantScope.runCompany(1L, () -> kolWriter.insert(kol, code, 1L));
    return kol.getId();
  }

  private void insertMapping(Long kolId, Long productId, String itemId, String source) {
    jdbc.update(
        "INSERT INTO crm_kol_item (id, tenant_id, kol_id, product_id, shop_id, weidian_item_id,"
            + " item_name, source, valid_from, create_time, update_time)"
            + " VALUES (nextval('crm_product_code_seq'), 1, ?, ?, 11, ?, NULL, ?, now(), now(),"
            + " now())",
        kolId,
        productId,
        itemId,
        source);
  }

  private JsonNode upload(long kolId, byte[] content, String token) throws Exception {
    MvcResult result =
        mvc.perform(
                multipart("/admin-api/kol/kols/" + kolId + "/promo-sheet-uploads")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "promo.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            content))
                    .header(TOKEN, token))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private MvcResult confirmRaw(long kolId, String uploadId, String token) throws Exception {
    return mvc.perform(
            post("/admin-api/kol/kols/" + kolId + "/promo-sheet-uploads/" + uploadId + "/confirm")
                .header(TOKEN, token))
        .andReturn();
  }

  @Test
  void shouldPreviewConfirmAndIndexSheetUploads() throws Exception {
    long kolA = seed()[0];

    JsonNode preview = upload(kolA, sampleSheet(), adminToken).at("/data");
    assertThat(preview.at("/toAdd").asInt()).isEqualTo(2);
    assertThat(preview.at("/existing").asInt()).isZero();
    assertThat(preview.at("/conflicts").asInt()).isEqualTo(1);
    assertThat(preview.at("/duplicates").asInt()).isEqualTo(1);
    assertThat(preview.at("/notInSheet").asInt()).isEqualTo(1);
    assertThat(preview.at("/linkRows").asInt()).isEqualTo(8);
    List<String> skipNames = new ArrayList<>();
    preview.at("/skipCounts").propertyNames().forEach(skipNames::add);
    assertThat(skipNames)
        .containsExactlyInAnyOrder("NON_WEIDIAN", "NO_ITEM_ID", "SHOP_NOT_REGISTERED", "IN_SHEET");
    assertThat(preview.at("/skipSamples").size()).isEqualTo(4);
    assertThat(preview.at("/skipSamples").get(0).at("/sheet").asString())
        .isEqualTo("ARCHIVE style");
    assertThat(preview.at("/skipSamples").get(0).at("/row").asInt()).isEqualTo(7);
    // The super administrator sees who owns the conflicting item.
    assertThat(preview.at("/conflictsDetail").get(0).at("/ownerKolCode").asString())
        .isEqualTo("KOL-BBB002");
    // New items carry the product code when one exists and stay empty otherwise.
    List<String> codes = new ArrayList<>();
    preview
        .at("/newItems")
        .forEach(
            item ->
                codes.add(
                    item.at("/productCode").isNull() ? null : item.at("/productCode").asString()));
    assertThat(codes).containsExactly("P000001", null);
    String uploadId = preview.at("/uploadId").asString();
    Long ttl = redis.getExpire("kol:promo-sheet:" + uploadId);
    assertThat(ttl).isNotNull().isBetween(29L * 60, 30L * 60);

    MvcResult confirmed = confirmRaw(kolA, uploadId, adminToken);
    assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
    JsonNode data = json.readTree(confirmed.getResponse().getContentAsString()).at("/data");
    assertThat(data.at("/inserted").asInt()).isEqualTo(2);
    assertThat(data.at("/fileName").asString()).isEqualTo("promo.xlsx");

    // The mappings exist with source, snapshot and the original product where known.
    Map<String, Object> withProduct =
        jdbc.queryForMap(
            "SELECT product_id, item_name, source, valid_to FROM crm_kol_item"
                + " WHERE kol_id = ? AND weidian_item_id = '1111111111'",
            kolA);
    assertThat(withProduct.get("product_id")).isEqualTo(99L);
    assertThat(withProduct.get("item_name")).isEqualTo("Silver Ring");
    assertThat(withProduct.get("source")).isEqualTo("SHEET_UPLOAD");
    assertThat(withProduct.get("valid_to")).isNull();
    Map<String, Object> withoutProduct =
        jdbc.queryForMap(
            "SELECT product_id, item_name FROM crm_kol_item"
                + " WHERE kol_id = ? AND weidian_item_id = '3333333333'",
            kolA);
    assertThat(withoutProduct.get("product_id")).isNull();
    assertThat(withoutProduct.get("item_name")).isEqualTo("Golden Ring");

    // The KOL's status is untouched (ADR-023) and the operation log records the outcome.
    assertThat(jdbc.queryForObject("SELECT status FROM crm_kol WHERE id = ?", Integer.class, kolA))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_oper_log WHERE module = 'KOL'"
                    + " AND action = 'Confirm promo sheet'",
                Integer.class))
        .isEqualTo(1);

    // The outbox drains and the linkitem index covers the mapping without an original product.
    sync.drain();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_link_index_outbox", Integer.class))
        .isZero();
    assertThat(String.valueOf(redis.opsForHash().get("linkitem:33", "3333333333")))
        .contains("\"kolItemId\"");
    assertThat(String.valueOf(redis.opsForHash().get("linkitem:11", "1111111111")))
        .contains("\"kolItemId\"");

    // Confirming twice is expired.
    MvcResult second = confirmRaw(kolA, uploadId, adminToken);
    assertThat(second.getResponse().getStatus()).isEqualTo(410);
    assertThat(json.readTree(second.getResponse().getContentAsString()).at("/errorCode").asString())
        .isEqualTo("KOL_PROMO_UPLOAD_EXPIRED");
  }

  @Test
  void shouldHideConflictOwnersFromStaff_andEnforceOwnScope() throws Exception {
    long[] kols = seed();
    long kolA = kols[0];
    long kolB = kols[1];
    String username = "bd-" + UUID.randomUUID();
    CompanySessions.createStaff(mvc, json, adminToken, PASSWORD, username, "BD", "OWN", null);
    String staffToken = CompanySessions.login(mvc, json, username, PASSWORD);
    jdbc.update(
        "UPDATE crm_kol SET bd_user_id = (SELECT id FROM sys_user WHERE username = ?)"
            + " WHERE id = ?",
        username,
        kolA);

    // A staff account confirms ownership is hidden, and only its own KOL accepts the upload.
    JsonNode preview = upload(kolA, sampleSheet(), staffToken).at("/data");
    assertThat(preview.at("/conflicts").asInt()).isEqualTo(1);
    assertThat(preview.at("/conflictsDetail").get(0).at("/ownerKolCode").isNull()).isTrue();
    assertThat(preview.at("/conflictsDetail").get(0).at("/ownerKolName").isNull()).isTrue();
    String uploadId = preview.at("/uploadId").asString();

    mvc.perform(
            multipart("/admin-api/kol/kols/" + kolB + "/promo-sheet-uploads")
                .file(
                    new MockMultipartFile(
                        "file",
                        "promo.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        sampleSheet()))
                .header(TOKEN, staffToken))
        .andExpect(status().isForbidden());

    // Another uploader, another KOL path and another company are all rejected; the KOL
    // unchanged in between keeps the upload itself valid for its uploader.
    assertThat(confirmRaw(kolA, uploadId, adminToken).getResponse().getStatus()).isEqualTo(403);
    assertThat(confirmRaw(kolB, uploadId, staffToken).getResponse().getStatus()).isEqualTo(403);
    String key = "kol:promo-sheet:" + uploadId;
    String payload = redis.opsForValue().get(key);
    redis.opsForValue().set(key, payload.replace("\"tenantId\":1", "\"tenantId\":2"));
    assertThat(confirmRaw(kolA, uploadId, staffToken).getResponse().getStatus()).isEqualTo(403);

    // A fresh upload by the staff account confirms normally.
    JsonNode own = upload(kolA, sampleSheet(), staffToken).at("/data");
    assertThat(
            confirmRaw(kolA, own.at("/uploadId").asString(), staffToken).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void shouldRejectNonXlsxUploads() throws Exception {
    long kolA = seed()[0];
    mvc.perform(
            multipart("/admin-api/kol/kols/" + kolA + "/promo-sheet-uploads")
                .file(new MockMultipartFile("file", "promo.csv", "text/csv", "a,b".getBytes()))
                .header(TOKEN, adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_PROMO_SHEET_INVALID"));
  }

  @Test
  void shouldKeepImportLimitAtTwentyMegabytesWhilePromoSheetsExceedIt() throws Exception {
    long kolA = seed()[0];
    byte[] tooLarge = new byte[21 * 1024 * 1024];
    // Import endpoints still cap at 20 MB in code (ExcelFiles.MAX_BYTES) even though the
    // Spring multipart ceiling now serves the 300 MB promo-sheet upload.
    mvc.perform(
            multipart("/admin-api/kol/kol-imports")
                .file(
                    new MockMultipartFile(
                        "file",
                        "kol.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        tooLarge))
                .header(TOKEN, adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_TOO_LARGE"));

    // A promo sheet above 20 MB passes its own endpoint: 21 MB of incompressible media plus rows.
    byte[] image = new byte[21 * 1024 * 1024];
    new java.util.Random(1).nextBytes(image);
    byte[] sheet;
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet page = book.createSheet("ARCHIVE style");
      Row head = page.createRow(1);
      head.createCell(1).setCellValue("NAME");
      head.createCell(4).setCellValue("LINK");
      linkRow(page, 2, "Big", kakobuy("111", "1111111111"));
      int picture = book.addPicture(image, XSSFWorkbook.PICTURE_TYPE_PNG);
      var drawing = page.createDrawingPatriarch();
      drawing.createPicture(drawing.createAnchor(0, 0, 0, 0, 0, 0, 1, 1), picture);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      book.write(out);
      sheet = out.toByteArray();
    }
    assertThat((long) sheet.length).isGreaterThan(20L * 1024 * 1024);
    JsonNode preview = upload(kolA, sheet, adminToken).at("/data");
    assertThat(preview.at("/toAdd").asInt()).isEqualTo(1);
  }

  @Test
  void shouldHideRetiredPortalItemListFromTheLoggedInKol() throws Exception {
    long[] kols = seed();
    long kolA = kols[0];
    activateKol(kolA, "pass-123-456");
    String tokenA = kolLogin("a@example.com", "pass-123-456");
    mvc.perform(get("/kol-api/items").header(KOL_TOKEN, tokenA))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }

  @Test
  void shouldPassCorsPreflightAndCrossOriginUpload_fromTheUploadSubDomain() throws Exception {
    long kolA = seed()[0];
    String origin = "https://admin.example.com";
    // Preflight: the interceptors must not answer the tokenless OPTIONS with 401.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/admin-api/kol/kols/" + kolA + "/promo-sheet-uploads")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-admin-token, x-company-id"))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                    .isEqualTo(origin));
    // An origin outside the whitelist is rejected without allow headers.
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/admin-api/kol/kols/" + kolA + "/promo-sheet-uploads")
                .header("Origin", "https://untrusted.example.com")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());

    // The actual cross-origin upload: same request the browser sends after the preflight.
    MvcResult upload =
        mvc.perform(
                multipart("/admin-api/kol/kols/" + kolA + "/promo-sheet-uploads")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "promo.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            sampleSheet()))
                    .header("Origin", origin)
                    .header(TOKEN, adminToken)
                    .header("X-Company-Id", "1"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(upload.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
    String uploadId =
        json.readTree(upload.getResponse().getContentAsString()).at("/data/uploadId").asString();

    // Cross-origin confirm keeps working on the main domain (no upload base URL override).
    MvcResult confirmed =
        mvc.perform(
                post("/admin-api/kol/kols/"
                        + kolA
                        + "/promo-sheet-uploads/"
                        + uploadId
                        + "/confirm")
                    .header(TOKEN, adminToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            json.readTree(confirmed.getResponse().getContentAsString())
                .at("/data/inserted")
                .asInt())
        .isEqualTo(2);
  }

  @Test
  void shouldCloseSheetUploadMappings_withoutProductOnIdleCleanup() throws Exception {
    long kolA = seed()[0];
    // Confirm an upload: one mapping with an original product, one without (both SHEET_UPLOAD).
    JsonNode preview = upload(kolA, sampleSheet(), adminToken).at("/data");
    String uploadId = preview.at("/uploadId").asString();
    assertThat(confirmRaw(kolA, uploadId, adminToken).getResponse().getStatus()).isEqualTo(200);

    // Task 25a idle cleanup ends every current mapping of the KOL; a null product_id and the
    // SHEET_UPLOAD source must not change that (the SQL filters only on kol_id + valid_to).
    // Three mappings are current: the seeded IMPORT one plus the two just confirmed.
    Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    // runCompany is void: the count is read back from the database below.
    TenantScope.runCompany(1L, () -> items.closeCurrentMappingsForKol(kolA, before, null));
    Integer closed =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_kol_item WHERE kol_id = ? AND valid_to IS NOT NULL",
            Integer.class,
            kolA);
    assertThat(closed).isEqualTo(3);
    Integer open =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_kol_item WHERE kol_id = ? AND valid_to IS NULL",
            Integer.class,
            kolA);
    assertThat(open).isZero();
    Integer closedWithoutProduct =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_kol_item WHERE kol_id = ? AND valid_to IS NOT NULL"
                + " AND product_id IS NULL AND source = 'SHEET_UPLOAD'",
            Integer.class,
            kolA);
    assertThat(closedWithoutProduct).isEqualTo(1);
  }

  private String kolLogin(String email, String password) throws Exception {
    String body =
        mvc.perform(
                post("/kol-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/token").asString();
  }

  private void activateKol(long kolId, String password) {
    accounts.activate(kolId, passwords.encode(password));
  }
}
