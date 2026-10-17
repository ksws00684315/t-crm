package com.tcrm.admin.security.api;

import java.time.Instant;
import java.util.List;

/**
 * Account persistence boundary supplied by the system module. An account is a person; its role,
 * data scope and permission codes depend on the company (ADR-022). {@link
 * AccountDTO#tenantEnabled()} is true only when the snapshot's company may be used: the membership
 * and the company are enabled (always true for a platform administrator, who has no company).
 */
public interface AccountStore {
  /**
   * Returns the case-insensitive account snapshot in the account's last company, or null; username
   * must be a validated ASCII login name. The company may be missing or unusable; login picks the
   * company from {@link #companies(Long)}.
   */
  AccountDTO getByUsername(String username);

  /**
   * Returns the active-row snapshot in the account's last company, or null; id must be a positive
   * Snowflake ID. Only for tokens issued before the current company was bound to the session, and
   * for platform administrators, who have no company.
   */
  AccountDTO getById(Long id);

  /**
   * Returns the active-row snapshot as a member of {@code tenantId}, or null when the account does
   * not exist. Without a live membership the role is null and the company is not usable.
   */
  AccountDTO getById(Long id, long tenantId);

  /**
   * Companies the account may enter now, ordered by company name: enabled memberships of enabled
   * companies. Empty for a platform administrator. Does not check the account status.
   */
  List<CompanyDTO> companies(Long id);

  /**
   * Records a successful login in UTC: the account's last login time and, for a member, its last
   * company and that membership's last login time. {@code tenantId} is null for a platform
   * administrator. Infrastructure failures propagate to the caller.
   */
  void recordLogin(Long id, Long tenantId, Instant time);

  /** Records a company switch: the account's last company and that membership's entry time. */
  void recordSwitch(Long id, long tenantId, Instant time);

  /**
   * Persists a BCrypt hash and increments the credential version, which revokes every token bound
   * to the old version; callers should still log the account out to clear its sessions.
   */
  void updatePassword(Long id, String hash);
}
