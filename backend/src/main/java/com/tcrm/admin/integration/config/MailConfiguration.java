package com.tcrm.admin.integration.config;

import com.tcrm.admin.integration.service.LoggingMailSender;
import com.tcrm.admin.integration.service.ResendMailSender;
import com.tcrm.admin.shared.MailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Chooses the mail transport from configuration: Resend when an API key is present, otherwise the
 * logging fallback. A configured key without a sender address is a deployment error and aborts
 * startup (task 12a §4.6).
 */
@Configuration
public class MailConfiguration {

  @Bean
  public MailSender mailSender(
      @Value("${crm.mail.api-key:}") String apiKey,
      @Value("${crm.mail.from:}") String from,
      @Value("${crm.mail.log-links:false}") boolean logLinks,
      ObjectMapper json) {
    if (apiKey == null || apiKey.isBlank()) {
      return new LoggingMailSender(logLinks);
    }
    if (from == null || from.isBlank()) {
      throw new IllegalStateException(
          "TCRM_MAIL_FROM must be set (e.g. \"T-CRM <no-reply@example.com>\") when"
              + " TCRM_MAIL_API_KEY is configured");
    }
    return new ResendMailSender(apiKey, from, json);
  }
}
