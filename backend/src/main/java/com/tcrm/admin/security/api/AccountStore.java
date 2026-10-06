package com.tcrm.admin.security.api;

import java.time.Instant;

/** Account persistence boundary supplied by the system module. */
public interface AccountStore {
  /**
   * Returns the case-insensitive account snapshot, or null; username must be a validated ASCII
   * login name.
   */
  AccountDTO getByUsername(String username);

  /** Returns the active-row snapshot, or null; id must be a positive Snowflake ID. */
  AccountDTO getById(Long id);

  /** Records a successful login in UTC; infrastructure failures propagate to the caller. */
  void updateLastLoginTime(Long id, Instant time);

  /**
   * Persists a BCrypt hash and increments the credential version, which revokes every token bound
   * to the old version; callers should still log the account out to clear its sessions.
   */
  void updatePassword(Long id, String hash);
}
