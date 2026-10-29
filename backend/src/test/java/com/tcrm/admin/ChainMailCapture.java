package com.tcrm.admin;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Captures invitation mail so a chain test can activate the creator the operator invited. */
@TestConfiguration
public class ChainMailCapture {
  public static final List<MailMessage> SENT = new CopyOnWriteArrayList<>();

  @Bean
  @Primary
  MailSender capturingMailSender() {
    return SENT::add;
  }
}
