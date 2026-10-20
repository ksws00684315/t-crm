package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issues and mails invitation tokens (design §4.2): every unused invitation of the KOL is
 * invalidated first, so only the newest link works. The caller decides scope and eligibility —
 * single creates resend automatically here, Excel imports do not (task 12a §4.4).
 */
@Component
public class KolInvites {
  private static final Logger LOG = LoggerFactory.getLogger(KolInvites.class);

  private final KolAccountStore accounts;
  private final KolAuthTokens tokens;
  private final KolMails mails;
  private final SystemConfigApi configs;

  @Autowired
  public KolInvites(
      KolAccountStore accounts, KolAuthTokens tokens, KolMails mails, SystemConfigApi configs) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mails = mails;
    this.configs = configs;
  }

  KolInvites(KolAccountStore accounts, KolAuthTokens tokens, KolMails mails) {
    this(
        accounts,
        tokens,
        mails,
        new SystemConfigApi() {
          @Override
          public String getValue(String key) {
            return "";
          }

          @Override
          public int getInt(String key) {
            return 72;
          }
        });
  }

  /**
   * Issues a fresh invitation for the KOL using the configured validity and sends the mail
   * asynchronously. A missing KOL (deleted concurrently) is skipped with a WARN; mail transport
   * failures are a WARN inside {@link KolMails}.
   */
  public void issueAndSend(Long kolId) {
    KolAccountDTO kol = accounts.getById(kolId);
    if (kol == null) {
      LOG.warn("Invitation skipped: KOL {} no longer exists", kolId);
      return;
    }
    String raw =
        tokens.issue(
            kol.tenantId(),
            kolId,
            KolAuthTokenDO.PURPOSE_INVITE,
            Instant.now().plus(Duration.ofHours(configs.getInt("kol.invite-ttl-hours"))));
    mails.sendInvite(kol.email(), kol.tenantName(), raw);
  }
}
