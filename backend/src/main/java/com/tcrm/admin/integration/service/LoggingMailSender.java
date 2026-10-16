package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback transport when {@code TCRM_MAIL_API_KEY} is empty (local development, test). It only
 * records the template name and the subject. With {@code crm.mail.log-links=true} the recipient and
 * the message text are logged as well, so a developer can read an invitation link, a reset link or
 * a registration code. Those values set a password or prove an email address, so this flag must
 * NEVER be enabled in production. With the flag off, the log line has neither the address nor the
 * body.
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
      LOG.info("[mail:{}] subject {}", message.template(), message.subject());
    }
  }
}
