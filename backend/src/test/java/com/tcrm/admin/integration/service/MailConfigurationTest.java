package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.config.MailConfiguration;
import com.tcrm.admin.shared.MailSender;
import com.tcrm.admin.shared.api.SystemConfigApi;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MailConfigurationTest {
  private final MailConfiguration configuration = new MailConfiguration();
  private final ObjectMapper json = new ObjectMapper();

  private static SystemConfigApi config(String from) {
    SystemConfigApi configs = mock(SystemConfigApi.class);
    when(configs.getValue("mail.from")).thenReturn(from);
    return configs;
  }

  @Test
  void shouldLogInsteadOfSending_whenNoApiKeyIsConfigured() {
    assertThat(configuration.mailSender("", false, json, config("")))
        .isInstanceOf(LoggingMailSender.class);
    assertThat(configuration.mailSender(null, false, json, config("")))
        .isInstanceOf(LoggingMailSender.class);
  }

  @Test
  void shouldFailStartup_whenApiKeyIsPresentWithoutSender() {
    assertThatThrownBy(() -> configuration.mailSender("re_key", false, json, config("")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_MAIL_FROM");
    assertThatThrownBy(() -> configuration.mailSender("re_key", false, json, config(null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldUseResend_whenApiKeyAndSenderAreConfigured() {
    MailSender sender =
        configuration.mailSender("re_key", true, json, config("SparkTrack <no-reply@example.com>"));
    assertThat(sender).isInstanceOf(ResendMailSender.class);
  }
}
