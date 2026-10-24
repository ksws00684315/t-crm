package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.api.SystemConfigApi;
import org.junit.jupiter.api.Test;

class WeidianAdminBaseUrlTest {
  @Test
  void shouldFollowCurrentAdminUrlConfiguration_whenCallbackUrlIsRead() {
    SystemConfigApi configs = mock(SystemConfigApi.class);
    when(configs.getValue("url.admin"))
        .thenReturn("https://first.example.com", "https://second.example.com");
    WeidianAdminBaseUrl baseUrl = new WeidianAdminBaseUrl(configs);

    assertThat(baseUrl.callbackUrl())
        .isEqualTo("https://first.example.com/admin-api/integration/weidian/oauth/callback");
    assertThat(baseUrl.callbackUrl())
        .isEqualTo("https://second.example.com/admin-api/integration/weidian/oauth/callback");
  }
}
