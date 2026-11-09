package com.tcrm.admin.kollibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.CompanySessions;
import com.tcrm.admin.WeidianTestProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Creator library of the creator-library edition (task 143 §3, requirements 1.2.1~1.2.6): CRUD,
 * duplicate detection including two simultaneous submissions, content records and their calculated
 * fields, the follow-up dialog, company isolation and the super-administrator-only delete.
 */
@Tag("docker")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class KolLibraryIT {
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = "X-Admin-Token";
  private static final String LIB = "/admin-api/kol-library/kols";

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
  private String platform;
  private CompanySessions.OpenedCompany library;
  private CompanySessions.OpenedCompany other;
  private String suffix;

  @Autowired
  KolLibraryIT(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper json, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.update("DELETE FROM crm_library_kol_content");
    jdbc.update("DELETE FROM crm_library_kol");
    jdbc.update("DELETE FROM sys_oper_log");
    jdbc.update("DELETE FROM sys_user_permission");
    jdbc.update("DELETE FROM sys_user WHERE username <> 'bootstrap'");
    jdbc.update("DELETE FROM sys_user_tenant WHERE user_id NOT IN (SELECT id FROM sys_user)");
    jdbc.update("DELETE FROM sys_tenant WHERE id <> 1");
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    platform = CompanySessions.login(mvc, json, "bootstrap", PASSWORD);
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    library =
        CompanySessions.openCompany(
            mvc, json, PASSWORD, "lib" + suffix, "lib-" + suffix, "KOL_LIBRARY");
    other =
        CompanySessions.openCompany(
            mvc, json, PASSWORD, "oth" + suffix, "oth-" + suffix, "KOL_LIBRARY");
  }

  private static Map<String, Object> profile(String username, String owner) {
    var body = new LinkedHashMap<String, Object>();
    body.put("platform", "TIKTOK");
    body.put("kolUsername", username);
    body.put("displayName", "Nina " + username);
    body.put("countryCode", "US");
    body.put("categories", List.of("BEAUTY_PERSONAL_CARE", "HOME_DAILY"));
    body.put("followers", 12000);
    body.put("avgViews", 8000);
    body.put("engagementRate", "3.25");
    body.put("profileUrl", "https://www.tiktok.com/@" + username);
    body.put("audienceCountry", "United States");
    body.put("audienceAgeRange", "AGE_25_34");
    body.put("audienceGender", "MOSTLY_FEMALE");
    body.put("contact", "+1 555 0100");
    body.put("contentQuality", "HIGH");
    body.put("salesPotential", "MEDIUM");
    body.put("cooperationAttitude", "HIGH");
    body.put("grade", "A");
    body.put("cooperationStage", "REPEAT_INVESTMENT");
    body.put("tags", List.of("高转化", "坑位费贵"));
    body.put("remark", "note");
    body.put("outreachStatus", "REPLIED");
    body.put("followUpOwner", owner);
    body.put("lastContactDate", "2026-11-01");
    body.put("sampleStatus", "SHIPPED");
    body.put("trackingNo", "SF123");
    body.put("sampleDate", "2026-10-30");
    return body;
  }

  private ResultActions create(String token, Map<String, Object> body) throws Exception {
    return mvc.perform(
        post(LIB)
            .header(TOKEN, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private String createOk(String token, String username, String owner) throws Exception {
    String response =
        create(token, profile(username, owner))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).at("/data/id").asText();
  }

  private Map<String, Object> content(String day) {
    var body = new LinkedHashMap<String, Object>();
    body.put("publishUrl", "https://www.tiktok.com/@x/video/" + day);
    body.put("publishDate", day);
    body.put("contentForm", "SHORT_VIDEO");
    body.put("clicks", 1200);
    body.put("orders", 30);
    body.put("gmv", "1000.00");
    body.put("sampleType", "GIFT");
    body.put("sampleCost", "30.00");
    body.put("slotFee", "50.00");
    body.put("commissionRate", "12.50");
    body.put("nextAction", "follow up");
    return body;
  }

  @Test
  void shouldCreateReadUpdateAndList_whenLibrarySuperAdminWorks() throws Exception {
    String token = library.token();
    String id = createOk(token, "@BeautyByAmy ", "Amy BD");
    mvc.perform(get(LIB + "/" + id).header(TOKEN, token))
        .andExpect(status().isOk())
        // The creator ID is stored normalised: no @, no spaces, lower case.
        .andExpect(jsonPath("$.data.kolUsername").value("beautybyamy"))
        .andExpect(jsonPath("$.data.platform").value("TIKTOK"))
        .andExpect(jsonPath("$.data.categories[0]").value("BEAUTY_PERSONAL_CARE"))
        .andExpect(jsonPath("$.data.tags[1]").value("坑位费贵"))
        .andExpect(jsonPath("$.data.outreachStatus").value("REPLIED"))
        .andExpect(jsonPath("$.data.contact").value("+1 555 0100"))
        .andExpect(jsonPath("$.data.totals.contentCount").value(0))
        .andExpect(jsonPath("$.data.totals.roi").doesNotExist());
    mvc.perform(get(LIB).header(TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].kolUsername").value("beautybyamy"))
        .andExpect(jsonPath("$.data.items[0].followUpOwner").value("Amy BD"));
    // Editing keeps the ID normalisation and replaces every group.
    var edit = profile("beautybyamy", "Bob BD");
    edit.put("grade", "S");
    edit.put("categories", List.of("FOOD_BEVERAGE"));
    mvc.perform(
            put(LIB + "/" + id)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.grade").value("S"))
        .andExpect(jsonPath("$.data.followUpOwner").value("Bob BD"))
        .andExpect(jsonPath("$.data.categories.length()").value(1));
  }

  @Test
  void shouldRejectDuplicate_onCreateAndOnEditToAnExistingId() throws Exception {
    String token = library.token();
    createOk(token, "@beautybyamy", "Amy BD");
    // Same creator written differently: at sign, spaces, upper case.
    create(token, profile(" BEAUTYBYAMY ", "Other BD"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_LIBRARY_DUPLICATE"))
        .andExpect(jsonPath("$.data.followUpOwner").value("Amy BD"))
        .andExpect(jsonPath("$.data.id").isNotEmpty());
    // A different platform is a different creator.
    var youtube = profile("beautybyamy", "Amy BD");
    youtube.put("platform", "YOUTUBE");
    create(token, youtube).andExpect(status().isOk());
    // Editing a second creator onto an existing ID is a duplicate too.
    String second = createOk(token, "secondone", "Amy BD");
    var clash = profile("beautybyamy", "Amy BD");
    mvc.perform(
            put(LIB + "/" + second)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(clash)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_LIBRARY_DUPLICATE"));
    // Editing a creator without changing its own ID is not a duplicate.
    String original =
        json.readTree(
                mvc.perform(
                        get(LIB)
                            .param("keyword", "beautybyamy")
                            .param("platform", "TIKTOK")
                            .header(TOKEN, token))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .at("/data/items/0/id")
            .asText();
    mvc.perform(
            put(LIB + "/" + original)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(profile("@BeautyByAmy", "Amy BD"))))
        .andExpect(status().isOk());
  }

  @Test
  void shouldKeepExactlyOneRow_whenTwoCreatesRace() throws Exception {
    String token = library.token();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> attempt =
          () -> create(token, profile("raceone", "Amy BD")).andReturn().getResponse().getStatus();
      List<Future<Integer>> results = new ArrayList<>();
      results.add(pool.submit(attempt));
      results.add(pool.submit(attempt));
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_library_kol WHERE kol_username = 'raceone'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldComputeContentValuesAndTotals_onTheServer() throws Exception {
    String token = library.token();
    String id = createOk(token, "calc", "Amy BD");
    String contentsPath = LIB + "/" + id + "/contents";
    String first =
        mvc.perform(
                post(contentsPath)
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(content("2026-10-01"))))
            .andExpect(status().isOk())
            // 1000 × 12.5% = 125.00; 30 + 50 + 125 = 205.00; 1000 / 205 = 4.88
            .andExpect(jsonPath("$.data.estimatedCommission").value("125.00"))
            .andExpect(jsonPath("$.data.totalCost").value("205.00"))
            .andExpect(jsonPath("$.data.actualRoi").value("4.88"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstId = json.readTree(first).at("/data/id").asText();
    // A second record whose total cost is 0 has a null ROI and counts empty fields as 0.
    var second = content("2026-10-05");
    second.put("gmv", null);
    second.put("sampleCost", null);
    second.put("slotFee", null);
    second.put("commissionRate", null);
    second.put("clicks", null);
    second.put("orders", null);
    mvc.perform(
            post(contentsPath)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(second)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.actualRoi").doesNotExist())
        .andExpect(jsonPath("$.data.totalCost").value("0.00"));
    mvc.perform(get(contentsPath).header(TOKEN, token))
        .andExpect(status().isOk())
        // Newest publication first.
        .andExpect(jsonPath("$.data[0].publishDate").value("2026-10-05"))
        .andExpect(jsonPath("$.data[1].publishDate").value("2026-10-01"));
    mvc.perform(get(LIB + "/" + id).header(TOKEN, token))
        .andExpect(jsonPath("$.data.totals.contentCount").value(2))
        .andExpect(jsonPath("$.data.totals.clicks").value(1200))
        .andExpect(jsonPath("$.data.totals.orders").value(30))
        .andExpect(jsonPath("$.data.totals.gmv").value("1000.00"))
        .andExpect(jsonPath("$.data.totals.totalCost").value("205.00"))
        .andExpect(jsonPath("$.data.totals.roi").value("4.88"));
    // Editing a record recalculates and is reflected in the totals.
    var edit = content("2026-10-01");
    edit.put("gmv", "2000.00");
    mvc.perform(
            put(contentsPath + "/" + firstId)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(edit)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.estimatedCommission").value("250.00"))
        .andExpect(jsonPath("$.data.totalCost").value("330.00"))
        .andExpect(jsonPath("$.data.actualRoi").value("6.06"));
    mvc.perform(get(LIB + "/" + id).header(TOKEN, token))
        .andExpect(jsonPath("$.data.totals.gmv").value("2000.00"));
  }

  @Test
  void shouldChangeOnlyTheFollowUpFields_whenTheFollowUpDialogSaves() throws Exception {
    String token = library.token();
    String id = createOk(token, "follow", "Amy BD");
    mvc.perform(
            put(LIB + "/" + id + "/follow-up")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "outreachStatus", "COOPERATING",
                            "lastContactDate", "2026-11-05",
                            "sampleStatus", "RECEIVED",
                            "trackingNo", "SF999",
                            "sampleDate", "2026-11-02"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outreachStatus").value("COOPERATING"))
        .andExpect(jsonPath("$.data.sampleStatus").value("RECEIVED"))
        .andExpect(jsonPath("$.data.trackingNo").value("SF999"))
        .andExpect(jsonPath("$.data.lastContactDate").value("2026-11-05"))
        // Everything else is untouched.
        .andExpect(jsonPath("$.data.grade").value("A"))
        .andExpect(jsonPath("$.data.followUpOwner").value("Amy BD"))
        .andExpect(jsonPath("$.data.displayName").value("Nina follow"));
  }

  @Test
  void shouldFilterTheList_byKeywordCategoryOutreachAndOwner() throws Exception {
    String token = library.token();
    createOk(token, "amybeauty", "Amy BD");
    var health = profile("healthnick", "Carol BD");
    health.put("categories", List.of("HEALTH_SUPPLEMENTS"));
    health.put("outreachStatus", "NOT_CONTACTED");
    health.put("displayName", "Wellness Queen");
    create(token, health).andExpect(status().isOk());
    mvc.perform(get(LIB).param("keyword", "AmyBeauty").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].kolUsername").value("amybeauty"));
    mvc.perform(get(LIB).param("keyword", "wellness").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(get(LIB).param("category", "HEALTH_SUPPLEMENTS").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].kolUsername").value("healthnick"));
    mvc.perform(get(LIB).param("category", "BEAUTY_PERSONAL_CARE").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(get(LIB).param("outreachStatus", "NOT_CONTACTED").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(get(LIB).param("followUpOwner", "carol").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(get(LIB).param("platform", "YOUTUBE").header(TOKEN, token))
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  void shouldRefuseOtherCompanyCreatorAndCountTheSameCreatorPerCompany() throws Exception {
    String mine = createOk(library.token(), "shared", "Amy BD");
    // The same creator may exist in another company.
    create(other.token(), profile("shared", "Other BD")).andExpect(status().isOk());
    mvc.perform(get(LIB + "/" + mine).header(TOKEN, other.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
    mvc.perform(get(LIB).header(TOKEN, other.token())).andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(delete(LIB + "/" + mine).header(TOKEN, other.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldRefuseContentOfAnotherCreator() throws Exception {
    String token = library.token();
    String first = createOk(token, "one", "Amy BD");
    String second = createOk(token, "two", "Amy BD");
    String created =
        mvc.perform(
                post(LIB + "/" + first + "/contents")
                    .header(TOKEN, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(content("2026-10-02"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String contentId = json.readTree(created).at("/data/id").asText();
    // Addressing that record under another creator is a 404, not an edit of the first creator.
    mvc.perform(
            put(LIB + "/" + second + "/contents/" + contentId)
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(content("2026-10-03"))))
        .andExpect(status().isNotFound());
    mvc.perform(delete(LIB + "/" + second + "/contents/" + contentId).header(TOKEN, token))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldAllowDeleteOnlyForSuperAdmin_andRemoveContentWithTheCreator() throws Exception {
    String token = library.token();
    String id = createOk(token, "gone", "Amy BD");
    mvc.perform(
            post(LIB + "/" + id + "/contents")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(content("2026-10-02"))))
        .andExpect(status().isOk());
    // A staff account with both library codes cannot delete, but may read and edit.
    long staff =
        CompanySessions.createStaff(
            mvc,
            json,
            token,
            PASSWORD,
            "staff-" + suffix,
            null,
            null,
            List.of("kol-library:view", "kol-library:edit"));
    String staffToken = CompanySessions.login(mvc, json, "staff-" + suffix, PASSWORD);
    mvc.perform(get(LIB + "/" + id).header(TOKEN, staffToken)).andExpect(status().isOk());
    mvc.perform(delete(LIB + "/" + id).header(TOKEN, staffToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    // A staff account with view only cannot write.
    CompanySessions.createStaff(
        mvc, json, token, PASSWORD, "viewer-" + suffix, null, null, List.of("kol-library:view"));
    String viewerToken = CompanySessions.login(mvc, json, "viewer-" + suffix, PASSWORD);
    create(viewerToken, profile("blocked", "Amy BD"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    mvc.perform(get(LIB + "/" + id).header(TOKEN, viewerToken)).andExpect(status().isOk());

    mvc.perform(delete(LIB + "/" + id).header(TOKEN, token)).andExpect(status().isOk());
    // Soft delete (standard 04) and, with it, the creator's content records.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_library_kol WHERE is_deleted = FALSE", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crm_library_kol_content WHERE is_deleted = FALSE",
                Integer.class))
        .isZero();
    mvc.perform(get(LIB + "/" + id).header(TOKEN, token)).andExpect(status().isNotFound());
    assertThat(staff).isPositive();
  }

  @Test
  void shouldJudgeTheRoleInTheCompanyOfTheToken_whenTheAccountBelongsToTwoCompanies()
      throws Exception {
    // One person, staff in the library company with both library codes (may edit, may not delete)
    // and staff of a distribution company. The role must come from the company the token works in,
    // never from the account's last company (ADR-022, requirement P1-15).
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    String multi = "two-" + suffix;
    String id = createOk(library.token(), "tgt" + suffix, "Amy BD");
    CompanySessions.OpenedCompany distribution =
        CompanySessions.openCompany(
            mvc, json, PASSWORD, "dist" + suffix, "dist-admin-" + suffix, "DISTRIBUTION");
    long userId =
        CompanySessions.createStaff(
            mvc, json, distribution.token(), PASSWORD, multi, "OPERATOR", "ALL", null);
    mvc.perform(
            post("/admin-api/platform/tenants/" + library.tenantId() + "/members")
                .header(TOKEN, platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", userId, "role", "STAFF"))))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin-api/system/users/" + userId + "/permissions")
                .header(TOKEN, library.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "permissions",
                            List.of("kol-library:view", "kol-library:edit"),
                            "dataScope",
                            "ALL"))))
        .andExpect(status().isOk());

    String token = CompanySessions.login(mvc, json, multi, PASSWORD);
    // Logged in at home in the distribution company (its last company), then switched to the
    // library.
    mvc.perform(get("/admin-api/kol-library/kols").header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_EDITION_FORBIDDEN"));
    mvc.perform(
            post("/admin-api/auth/company")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + library.tenantId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("STAFF"));
    mvc.perform(get(LIB).header(TOKEN, token)).andExpect(status().isOk());
    // The role is the one in this company: 403, not a deletion borrowed from another company.
    mvc.perform(delete(LIB + "/" + id).header(TOKEN, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    assertThat(
            jdbc.queryForObject(
                "SELECT is_deleted FROM crm_library_kol WHERE id = ?",
                Boolean.class,
                Long.parseLong(id)))
        .isFalse();
  }

  @Test
  void shouldStoreTheProfileUrlExactly_asTyped() throws Exception {
    String token = library.token();
    String url = "https://www.tiktok.com/@t143_beauty_probe";
    var body = profile("urlprobe", "Amy BD");
    body.put("profileUrl", url);
    String created =
        create(token, body)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(created).at("/data/id").asText();
    assertThat(json.readTree(created).at("/data/profileUrl").asText()).isEqualTo(url);
    assertThat(
            jdbc.queryForObject(
                "SELECT profile_url FROM crm_library_kol WHERE id = ?",
                String.class,
                Long.parseLong(id)))
        .isEqualTo(url);
    // A link that is not http(s) is refused rather than stored.
    var invalid = profile("badlink", "Amy BD");
    invalid.put("profileUrl", "http ://www.tiktok.com/@bad");
    create(token, invalid).andExpect(status().isBadRequest());
  }

  @Test
  void shouldAllowTheSameCreatorAgain_afterItWasDeleted() throws Exception {
    String token = library.token();
    String id = createOk(token, "again", "Amy BD");
    mvc.perform(delete(LIB + "/" + id).header(TOKEN, token)).andExpect(status().isOk());
    create(token, profile("again", "Amy BD")).andExpect(status().isOk());
  }

  @Test
  void shouldRejectInvalidInput_withAValidationAnswer() throws Exception {
    String token = library.token();
    var noId = profile("", "Amy BD");
    create(token, noId).andExpect(status().isBadRequest());
    var onlyAt = profile("@", "Amy BD");
    create(token, onlyAt).andExpect(status().isBadRequest());
    var noCategories = profile("nocat", "Amy BD");
    noCategories.put("categories", List.of());
    create(token, noCategories).andExpect(status().isBadRequest());
    var unknownCategory = profile("badcat", "Amy BD");
    unknownCategory.put("categories", List.of("NOT_A_CATEGORY"));
    create(token, unknownCategory).andExpect(status().isBadRequest());
    var noOwner = profile("noowner", " ");
    create(token, noOwner).andExpect(status().isBadRequest());
    var negative = profile("negative", "Amy BD");
    negative.put("followers", -1);
    create(token, negative).andExpect(status().isBadRequest());
    var badRate = profile("badrate", "Amy BD");
    badRate.put("engagementRate", "100.01");
    create(token, badRate).andExpect(status().isBadRequest());
    var badUrl = profile("badurl", "Amy BD");
    badUrl.put("profileUrl", "not a url");
    create(token, badUrl).andExpect(status().isBadRequest());
    create(token, profile("ok", "Amy BD")).andExpect(status().isOk());
  }

  @Test
  void shouldWriteAnAuditRow_perWriteWithoutTheContact() throws Exception {
    String token = library.token();
    String id = createOk(token, "audited", "Amy BD");
    mvc.perform(
            put(LIB + "/" + id + "/follow-up")
                .header(TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"outreachStatus\":\"COOPERATING\",\"sampleStatus\":\"SHIPPED\","
                        + "\"trackingNo\":\"SF1\"}"))
        .andExpect(status().isOk());
    List<String> actions =
        jdbc.queryForList(
            "SELECT action FROM sys_oper_log WHERE module = 'Kol library' ORDER BY oper_time",
            String.class);
    assertThat(actions).contains("Create", "Update follow-up");
    List<String> params =
        jdbc.queryForList(
            "SELECT request_params FROM sys_oper_log WHERE action = 'Create'", String.class);
    assertThat(params).isNotEmpty();
    assertThat(params.getFirst()).doesNotContain("555 0100");
  }
}
