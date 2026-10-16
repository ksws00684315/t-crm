package com.tcrm.admin.security.api;

import java.time.Instant;
import java.util.List;

/** KOL account persistence boundary supplied by the kol module. All reads run in system scope. */
public interface KolAccountStore {
  /**
   * Returns every active-row account sharing this lowercase email across all companies, or an empty
   * list; email must already be normalized (design §3.4: one email may have an account per
   * company).
   */
  List<KolAccountDTO> listByEmail(String email);

  /** Returns the active-row snapshot, or null; id must be a positive Snowflake ID. */
  KolAccountDTO getById(Long id);

  /** Records a successful login in UTC; infrastructure failures propagate to the caller. */
  void updateLastLoginTime(Long id, Instant time);

  /**
   * Stores the BCrypt hash, marks the account activated and increments the credential version in
   * one write (the column constraint requires hash and status to move together).
   */
  void activate(Long id, String hash);

  /**
   * Persists a BCrypt hash and increments the credential version, which revokes every token bound
   * to the old version; callers should still log the account out to clear its sessions.
   */
  void updatePassword(Long id, String hash);
}
