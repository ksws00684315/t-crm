package com.tcrm.admin.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.integration.api.WeidianAppView;
import com.tcrm.admin.integration.api.WeidianAuthorizeView;
import com.tcrm.admin.integration.service.WeidianIntegrationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WeidianControllerTest {
  private final WeidianIntegrationService integration = mock(WeidianIntegrationService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new WeidianController(integration)).build();
  }

  @Test
  void shouldReturnAppViewWithoutAnySecret() throws Exception {
    when(integration.app())
        .thenReturn(new WeidianAppView("public-key", true, Instant.EPOCH, "https://admin.test/cb"));

    var response =
        mvc.perform(get("/admin-api/integration/weidian/app"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(response.getContentAsString())
        .contains("public-key", "secretConfigured", "callbackUrl", "https://admin.test/cb");
    assertThat(response.getContentAsString())
        .doesNotContain("private-secret", "appSecret", "appSecretCipher");
  }

  @Test
  void shouldExposeCompanyShopStatusList() throws Exception {
    when(integration.shops()).thenReturn(List.of());

    mvc.perform(get("/admin-api/integration/weidian/shops")).andExpect(status().isOk());
  }

  @Test
  void shouldRouteAppAndSelfUseWrites() throws Exception {
    mvc.perform(
            put("/admin-api/integration/weidian/app")
                .contentType("application/json")
                .content("{\"appKey\":\"app\",\"appSecret\":\"secret\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin-api/integration/weidian/shops/12/self-use")
                .contentType("application/json")
                .content("{\"appKey\":\"app\",\"appSecret\":\"secret\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldRouteAuthorizationCheckAndPublicCallback() throws Exception {
    when(integration.authorizeServiceShop(12L))
        .thenReturn(new WeidianAuthorizeView("https://oauth.example.test/authorize"));
    when(integration.callback(null, "0123456789abcdefghijklmnopqrstuv"))
        .thenReturn(new WeidianIntegrationService.CallbackResult(true, null));
    when(integration.callback(null, "invalid"))
        .thenReturn(new WeidianIntegrationService.CallbackResult(false, "STATE_INVALID"));

    mvc.perform(post("/admin-api/integration/weidian/shops/12/authorizations"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/admin-api/integration/weidian/oauth/callback")
                .param("state", "0123456789abcdefghijklmnopqrstuv"))
        .andExpect(status().isFound())
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/product/shops?weidianAuth=success"));
    mvc.perform(get("/admin-api/integration/weidian/oauth/callback").param("state", "invalid"))
        .andExpect(status().isFound());
  }

  @Test
  void shouldRouteConnectivityCheck() throws Exception {
    mvc.perform(post("/admin-api/integration/weidian/shops/12/checks")).andExpect(status().isOk());
  }
}
