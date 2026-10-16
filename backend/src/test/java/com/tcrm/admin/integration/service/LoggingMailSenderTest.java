package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.shared.MailMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingMailSenderTest {
  private static final String CODE = "654321";
  private static final String RECIPIENT = "alice.secret@example.com";

  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingMailSender.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    logger.setLevel(Level.INFO);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void shouldOmitTheRecipientAndTheBody_whenLinkLoggingIsOff() {
    new LoggingMailSender(false).send(message());
    assertThat(appender.list).hasSize(1);
    String line = appender.list.getFirst().getFormattedMessage();
    assertThat(line).contains("kol-register-code").contains("Your verification code");
    assertThat(line).doesNotContain(CODE).doesNotContain(RECIPIENT).doesNotContain("alice.secret");
  }

  @Test
  void shouldIncludeTheCode_whenLinkLoggingIsOn() {
    new LoggingMailSender(true).send(message());
    String line = appender.list.getFirst().getFormattedMessage();
    assertThat(line).contains(CODE).contains(RECIPIENT);
  }

  private static MailMessage message() {
    return new MailMessage(
        RECIPIENT,
        "kol-register-code",
        "[Acme] Your verification code / 您的验证码",
        "code\n\n" + CODE + "\n",
        "<p>" + CODE + "</p>");
  }
}
