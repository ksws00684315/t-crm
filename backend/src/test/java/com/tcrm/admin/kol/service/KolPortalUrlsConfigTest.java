package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.api.SystemConfigApi;
import org.junit.jupiter.api.Test;

class KolPortalUrlsConfigTest {
  @Test
  void shouldUseCurrentPortalAddressWhenBuildingInviteAndRegistrationLinks() {
    SystemConfigApi configs = mock(SystemConfigApi.class);
    when(configs.getValue("url.kol-portal"))
        .thenReturn("https://first.example", "http://localhost:8832");
    KolPortalUrls urls = new KolPortalUrls(configs);
    assertThat(urls.registerUrl("AB12CD34"))
        .isEqualTo("https://first.example/register?code=AB12CD34");
    assertThat(urls.activateUrl("token")).isEqualTo("http://localhost:8832/activate?token=token");
  }
}
