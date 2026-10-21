package com.tcrm.admin.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.api.SystemConfigApi;
import org.junit.jupiter.api.Test;

class PublicSiteConfigControllerTest {
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final PublicSiteConfigController controller = new PublicSiteConfigController(configs);

  @Test
  void shouldExposeSiteNameAndUploadUrlToAdmin() {
    when(configs.getValue("site.name")).thenReturn("SparkTrack");
    when(configs.getValue("url.upload")).thenReturn("https://upload.example.com");

    var response = controller.admin();

    assertThat(response.data().siteName()).isEqualTo("SparkTrack");
    assertThat(response.data().uploadBaseUrl()).isEqualTo("https://upload.example.com");
  }

  @Test
  void shouldExposeSiteNameToKolPortal() {
    when(configs.getValue("site.name")).thenReturn("Creator CRM");

    var response = controller.kol();

    assertThat(response.data().siteName()).isEqualTo("Creator CRM");
  }
}
