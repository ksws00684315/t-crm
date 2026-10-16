package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issues and mails invitation tokens (design §4.2): every unused invitation of the KOL is
 * invalidated first, so only the newest link works. The caller decides scope and eligibility —
 * single creates resend automatically here, Excel imports do not (task 12a §4.4).
 */
@Component
public class KolInvites {
  static final Duration INVITE_TTL = Duration.ofHours(72);
  private static final Logger LOG = LoggerFactory.getLogger(KolInvites.class);

  private final KolAccountStore accounts;
  private final KolAuthTokens tokens;
  private final KolMails mails;

  public KolInvites(KolAccountStore accounts, KolAuthTokens tokens, KolMails mails) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.mails = mails;
  }

  /**
   * Issues a fresh 72-hour invitation for the KOL and sends the mail asynchronously. A missing KOL
   * (deleted concurrently) is skipped with a WARN; mail transport failures are a WARN inside {@link
   * KolMails}.
   */
  public void issueAndSend(Long kolId) {
    KolAccountDTO kol = accounts.getById(kolId);
    if (kol == null) {
      LOG.warn("Invitation skipped: KOL {} no longer exists", kolId);
      return;
    }
    String raw =
        tokens.issue(
            kol.tenantId(), kolId, KolAuthTokenDO.PURPOSE_INVITE, Instant.now().plus(INVITE_TTL));
    mails.sendInvite(kol.email(), kol.tenantName(), raw);
  }
}
