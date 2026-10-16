package com.tcrm.admin.shared;

/**
 * Outgoing email. Templates are built by the calling module; both text and a simple HTML rendering
 * are always provided (design §2.4: mails carry English and Chinese in one message).
 *
 * @param template short template identifier for logs ({@code kol-invite}, {@code kol-reset})
 */
public record MailMessage(String to, String template, String subject, String text, String html) {}
