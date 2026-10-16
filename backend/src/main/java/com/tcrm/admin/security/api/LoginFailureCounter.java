package com.tcrm.admin.security.api;

/**
 * Login failure throttling boundary (ADR-008) for other modules: the KOL login counts on the same
 * three scopes and thresholds as the administrator login, but on its own Redis keys so neither
 * namespace affects the other (task 12a §4.4).
 */
public interface LoginFailureCounter {
  /** Throws AUTH_ACCOUNT_LOCKED (429) while any scope of this attempt is locked. */
  void check(String username, String ip);

  /** Records a failed attempt on every scope; throws when one reaches its limit. */
  void fail(String username, String ip);

  /** Clears only the username + IP counter after a successful login (ADR-008). */
  void clear(String username, String ip);
}
