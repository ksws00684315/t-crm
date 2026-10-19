package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.shared.MailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class KolMailsTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(KolMails.class);
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
  void shouldMaskTheRecipient_whenTheTransportFails() {
    MailSender sender = mock(MailSender.class);
    doThrow(new RuntimeException("smtp down")).when(sender).send(any());
    KolMailTemplates templates = new KolMailTemplates(new KolPortalUrls("https://kol.example.com"));
    new KolMails(sender, templates).sendInvite("alice.secret@example.com", "Acme", "safe-token");
    assertThat(appender.list).hasSize(1);
    String line = appender.list.getFirst().getFormattedMessage();
    assertThat(line).contains("kol-invite").contains("a***@example.com").contains("smtp down");
    assertThat(line).doesNotContain("alice.secret").doesNotContain("safe-token");
  }

  @Test
  void shouldKeepTheDomainAndDropTheLocalPart() {
    assertThat(KolMails.mask("alice.secret@example.com")).isEqualTo("a***@example.com");
    assertThat(KolMails.mask("not-an-email")).isEqualTo("email#redacted");
    assertThat(KolMails.mask(null)).isEqualTo("email#redacted");
  }
}
