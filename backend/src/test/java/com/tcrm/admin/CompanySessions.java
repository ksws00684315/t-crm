package com.tcrm.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Logs tests in as a company super administrator. Bootstrap is a platform administrator and cannot
 * call company APIs.
 */
public final class CompanySessions {
  public static final String COMPANY_ADMIN = "company-admin";

  private CompanySessions() {}

  /** Super administrator of the default company ({@code tenant_id = 1}). */
  public static String companyAdmin(MockMvc mvc, ObjectMapper json, String password)
      throws Exception {
    return companyAdmin(mvc, json, password, 1L, COMPANY_ADMIN);
  }

  /**
   * Creates a super administrator for {@code tenantId} when the username is free, then logs in.
   * {@code 409} means a previous test already created that username.
   */
  public static String companyAdmin(
      MockMvc mvc, ObjectMapper json, String password, long tenantId, String username)
      throws Exception {
    String platform = login(mvc, json, "bootstrap", password);
    int created =
        mvc.perform(
                post("/admin-api/platform/tenants/" + tenantId + "/admins")
                    .header("X-Admin-Token", platform)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username", username, "password", password, "nickname", username))))
            .andReturn()
            .getResponse()
            .getStatus();
    if (created != 200 && created != 409) {
      throw new AssertionError("creating company admin " + username + " returned " + created);
    }
    return login(mvc, json, username, password);
  }

  /** Creates a company and its first super administrator. */
  public static OpenedCompany openCompany(
      MockMvc mvc, ObjectMapper json, String password, String tenantCode, String username)
      throws Exception {
    String platform = login(mvc, json, "bootstrap", password);
    String body =
        mvc.perform(
                post("/admin-api/platform/tenants")
                    .header("X-Admin-Token", platform)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("tenantCode", tenantCode, "name", username))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long tenantId = Long.parseLong(json.readTree(body).at("/data/id").asText());
    String adminBody =
        mvc.perform(
                post("/admin-api/platform/tenants/" + tenantId + "/admins")
                    .header("X-Admin-Token", platform)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username", username, "password", password, "nickname", username))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long userId = Long.parseLong(json.readTree(adminBody).at("/data/id").asText());
    return new OpenedCompany(tenantId, userId, username, login(mvc, json, username, password));
  }

  /**
   * Creates a staff account in the caller's company. A null permission list grants the template; an
   * empty list grants nothing. A null data scope uses the template default.
   */
  public static long createStaff(
      MockMvc mvc,
      ObjectMapper json,
      String adminToken,
      String password,
      String username,
      String postTemplate,
      String dataScope,
      List<String> permissions)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("username", username);
    body.put("password", password);
    body.put("nickname", username);
    body.put("role", "STAFF");
    body.put("postTemplate", postTemplate);
    if (dataScope != null) {
      body.put("dataScope", dataScope);
    }
    if (permissions != null) {
      body.put("permissions", permissions);
    }
    String response =
        mvc.perform(
                post("/admin-api/system/users")
                    .header("X-Admin-Token", adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(json.readTree(response).at("/data/id").asText());
  }

  public static String login(MockMvc mvc, ObjectMapper json, String username, String password)
      throws Exception {
    String body =
        mvc.perform(
                post("/admin-api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Captchas.loginBody(mvc, json, username, password))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/token").asText();
  }

  public record OpenedCompany(long tenantId, long userId, String username, String token) {}
}
