package com.tcrm.admin.security.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** Internal KOL account snapshot; the password hash is never serialized. */
public record KolAccountDTO(
    Long id,
    String kolCode,
    String name,
    String email,
    String country,
    @JsonIgnore String passwordHash,
    Integer status,
    Integer accountStatus,
    int credentialVersion,
    Instant lastLoginTime,
    Long tenantId,
    String tenantName,
    boolean tenantEnabled,
    String phone,
    String whatsapp,
    String sheetUrl) {

  /** Snapshot without the profile fields shown on GET /kol-api/me (phone, whatsapp, sheetUrl). */
  public KolAccountDTO(
      Long id,
      String kolCode,
      String name,
      String email,
      String country,
      String passwordHash,
      Integer status,
      Integer accountStatus,
      int credentialVersion,
      Instant lastLoginTime,
      Long tenantId,
      String tenantName,
      boolean tenantEnabled) {
    this(
        id,
        kolCode,
        name,
        email,
        country,
        passwordHash,
        status,
        accountStatus,
        credentialVersion,
        lastLoginTime,
        tenantId,
        tenantName,
        tenantEnabled,
        null,
        null,
        null);
  }

  /** KOL row status (design §5.2): 1 enabled, anything else disabled. */
  public boolean enabled() {
    return Integer.valueOf(1).equals(status);
  }

  /** An activated account has set a password (design §3.4); unactivated accounts cannot log in. */
  public boolean activated() {
    return Integer.valueOf(1).equals(accountStatus);
  }

  /** Copies the snapshot with the credential version a password change just bumped to. */
  public KolAccountDTO withCredentialVersion(int version) {
    return new KolAccountDTO(
        id,
        kolCode,
        name,
        email,
        country,
        passwordHash,
        status,
        accountStatus,
        version,
        lastLoginTime,
        tenantId,
        tenantName,
        tenantEnabled,
        phone,
        whatsapp,
        sheetUrl);
  }
}
