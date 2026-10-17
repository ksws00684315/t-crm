package com.tcrm.admin.kol.api;

/**
 * Internal copy-token auth and quota for the external product-link APIs (task 17b). No login
 * session is required; the company comes from the token.
 */
public interface CopyTokenApi {
  /**
   * Authenticates by plaintext. Accepts only a currently valid token; invalid, expired, exhausted,
   * revoked, or a disabled KOL each throw the matching error code.
   */
  CopyTokenGrantDTO authenticate(String token);

  /**
   * Atomically consumes {@code count} from the remaining quota and updates {@code last_used_at}.
   * Returns false without changing the row when the remaining quota is insufficient.
   */
  boolean consume(long tokenId, int count);
}
