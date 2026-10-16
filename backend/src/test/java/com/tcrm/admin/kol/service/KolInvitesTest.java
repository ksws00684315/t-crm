package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolInvitesTest {
  private final KolAccountStore accounts = mock(KolAccountStore.class);
  private final KolAuthTokens tokens = mock(KolAuthTokens.class);
  private final KolMails mails = mock(KolMails.class);
  private final KolInvites invites = new KolInvites(accounts, tokens, mails);

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        7L, "KOL0007", "Alice", "kol@example.com", "US", null, 1, 0, 0, null, 2L, "Tenant", true);
  }

  @Test
  void shouldIssueTokenWith72hExpiry_andMailTheKol() {
    when(accounts.getById(7L)).thenReturn(kol());
    when(tokens.issue(
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(KolAuthTokenDO.PURPOSE_INVITE),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn("raw-token");
    invites.issueAndSend(7L);
    var expiry = ArgumentCaptor.forClass(Instant.class);
    verify(tokens)
        .issue(
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(KolAuthTokenDO.PURPOSE_INVITE),
            expiry.capture());
    assertThat(java.time.Duration.between(Instant.now(), expiry.getValue()))
        .isCloseTo(KolInvites.INVITE_TTL, java.time.Duration.ofSeconds(5));
    verify(mails).sendInvite("kol@example.com", "Tenant", "raw-token");
  }

  @Test
  void shouldSkipSilently_whenKolVanished() {
    when(accounts.getById(7L)).thenReturn(null);
    invites.issueAndSend(7L);
    verify(tokens, org.mockito.Mockito.never())
        .issue(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    verify(mails, org.mockito.Mockito.never())
        .sendInvite(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void shouldPropagateMailFailureAsWarnOnly() {
    // KolMails itself catches transport failures; the invite flow must not see exceptions.
    var failingMails =
        new KolMails(
            message -> {
              throw new IllegalStateException("transport down");
            },
            new KolMailTemplates(new KolPortalUrls("https://kol.example.com")));
    var failingInvites = new KolInvites(accounts, tokens, failingMails);
    when(accounts.getById(7L)).thenReturn(kol());
    when(tokens.issue(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn("raw-token");
    failingInvites.issueAndSend(7L); // must not throw
  }
}
