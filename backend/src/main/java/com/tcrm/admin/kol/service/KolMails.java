package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Sends the KOL auth mails off the request thread. A transport failure is a WARN, never a rollback
 * (task 12a §4.4): the invitation token stays valid and the back office can resend it. Message
 * content (subject aside) is not logged, so links and verification codes never reach the logs. A
 * transport failure names the template and a masked recipient, never the full address.
 */
@Component
public class KolMails {
  private static final Logger LOG = LoggerFactory.getLogger(KolMails.class);

  private final MailSender sender;
  private final KolMailTemplates templates;

  public KolMails(MailSender sender, KolMailTemplates templates) {
    this.sender = sender;
    this.templates = templates;
  }

  /** Invitation mail after a back-office create or resend; async so the API answers at once. */
  @Async
  public void sendInvite(String to, String company, String token) {
    send(templates.invite(to, company, token));
  }

  /** One reset mail carrying every company's link for the address; async like the invitation. */
  @Async
  public void sendReset(String to, List<KolMailTemplates.ResetLink> links) {
    send(templates.reset(to, links));
  }

  private void send(MailMessage message) {
    try {
      sender.send(message);
    } catch (RuntimeException e) {
      LOG.warn(
          "Failed to send mail template {} to {}: {}",
          message.template(),
          mask(message.to()),
          e.getMessage());
    }
  }

  /** Keeps the domain so a failure can be told apart, and drops the local part. */
  static String mask(String email) {
    if (email == null || email.isBlank()) {
      return "email#redacted";
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "email#redacted";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
