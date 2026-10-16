package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolAuthTokenMapper;
import com.tcrm.admin.kol.model.KolAuthTokenDO;
import com.tcrm.admin.shared.TenantScope;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time token store behind invitations and password resets. All statements run in system scope:
 * the public activate and reset endpoints validate tokens before any login exists, and the token
 * digest alone identifies the owning company (task 12a §4.4). Marking used is deliberately not
 * transactional here — callers combine it with their own writes.
 */
@Component
public class KolAuthTokens {
  private final KolAuthTokenMapper tokens;
  private final TransactionTemplate tx;

  public KolAuthTokens(KolAuthTokenMapper tokens, TransactionTemplate tx) {
    this.tokens = tokens;
    this.tx = tx;
  }

  /**
   * Invalidates every unused token of this KOL and purpose and inserts a fresh one; returns the raw
   * token for the mail link. The digest in the row is the only stored form.
   */
  public String issue(Long tenantId, Long kolId, String purpose, Instant expiresAt) {
    String raw = SecureTokens.generate();
    TenantScope.runSystem(
        "issue one-time KOL token",
        () ->
            tx.executeWithoutResult(
                status -> {
                  tokens.invalidateUnused(kolId, purpose);
                  KolAuthTokenDO row = new KolAuthTokenDO();
                  row.setTenantId(tenantId);
                  row.setKolId(kolId);
                  row.setPurpose(purpose);
                  row.setTokenHash(SecureTokens.hash(raw));
                  row.setExpiresAt(expiresAt);
                  row.setCreateTime(Instant.now());
                  tokens.insert(row);
                }));
    return raw;
  }

  /** Returns the live row for this raw token, or null when unknown, expired or already used. */
  public KolAuthTokenDO findLive(String rawToken) {
    return TenantScope.callSystem(
        "validate one-time KOL token", () -> tokens.findLiveByHash(SecureTokens.hash(rawToken)));
  }

  /** Marks the row used; 0 means a concurrent request consumed it first. */
  public int markUsed(Long id) {
    return TenantScope.callSystem("consume one-time KOL token", () -> tokens.markUsed(id));
  }

  /** Marks every unused token of this KOL and purpose used. */
  public void invalidateUnused(Long kolId, String purpose) {
    TenantScope.runSystem(
        "invalidate one-time KOL tokens", () -> tokens.invalidateUnused(kolId, purpose));
  }
}
