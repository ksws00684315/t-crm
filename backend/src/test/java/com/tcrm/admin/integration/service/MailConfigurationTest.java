package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.integration.config.MailConfiguration;
import com.tcrm.admin.shared.MailSender;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MailConfigurationTest {
  private final MailConfiguration configuration = new MailConfiguration();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void shouldLogInsteadOfSending_whenNoApiKeyIsConfigured() {
    assertThat(configuration.mailSender("", "", false, json)).isInstanceOf(LoggingMailSender.class);
    assertThat(configuration.mailSender(null, "", false, json))
        .isInstanceOf(LoggingMailSender.class);
  }

  @Test
  void shouldFailStartup_whenApiKeyIsPresentWithoutSender() {
    assertThatThrownBy(() -> configuration.mailSender("re_key", "", false, json))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_MAIL_FROM");
    assertThatThrownBy(() -> configuration.mailSender("re_key", null, false, json))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldUseResend_whenApiKeyAndSenderAreConfigured() {
    MailSender sender =
        configuration.mailSender("re_key", "SparkTrack <no-reply@example.com>", true, json);
    assertThat(sender).isInstanceOf(ResendMailSender.class);
  }
}
