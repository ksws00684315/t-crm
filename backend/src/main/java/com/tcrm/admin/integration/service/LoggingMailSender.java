package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback transport when {@code TCRM_MAIL_API_KEY} is empty (local development, test). It only
 * records the recipient and the template name. With {@code crm.mail.log-links=true} the message
 * text is logged as well so the developer can open the invitation or reset link — the links set or
 * reset a KOL password, so this flag must NEVER be enabled in production.
 */
public class LoggingMailSender implements MailSender {
  private static final Logger LOG = LoggerFactory.getLogger(LoggingMailSender.class);

  private final boolean logLinks;

  public LoggingMailSender(boolean logLinks) {
    this.logLinks = logLinks;
  }

  @Override
  public void send(MailMessage message) {
    if (logLinks) {
      LOG.info(
          "[mail:{}] to {} subject {} text {}",
          message.template(),
          message.to(),
          message.subject(),
          message.text());
    } else {
      LOG.info("[mail:{}] to {} subject {}", message.template(), message.to(), message.subject());
    }
  }
}
