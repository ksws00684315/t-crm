package com.tcrm.admin.shared;

/**
 * Mail transport boundary (task 12a §4.5). The contract lives in {@code shared}, not {@code
 * integration.api}: the kol module sends mails, and a kol → integration dependency would close the
 * slice cycle kol → integration → product → kol that {@code ArchitectureTest} forbids. The Resend
 * and logging implementations remain in the integration module.
 */
public interface MailSender {
  /**
   * Sends the message or throws a runtime exception after the transport's retries are exhausted;
   * callers decide how failures are reported and must never log the message body.
   */
  void send(MailMessage message);
}
