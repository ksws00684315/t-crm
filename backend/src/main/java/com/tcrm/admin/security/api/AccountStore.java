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

  /** Persists a BCrypt hash; caller must invalidate all existing tokens after success. */
  void updatePassword(Long id, String hash);
}
