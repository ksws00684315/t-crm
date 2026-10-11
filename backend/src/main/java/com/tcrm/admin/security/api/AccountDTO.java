package com.tcrm.admin.security.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** Internal account snapshot; password hash is never serialized. */
public record AccountDTO(
    Long id,
    String username,
    @JsonIgnore String passwordHash,
    String nickname,
    String email,
    Role role,
    DataScope dataScope,
    Integer status,
    Instant lastLoginTime,
    Instant createTime,
    int credentialVersion,
    Long tenantId,
    String tenantName,
    @JsonIgnore boolean tenantEnabled) {
  /**
   * Copies the immutable snapshot with the UTC successful-login time; credentials remain internal.
   */
  public AccountDTO withLastLoginTime(Instant time) {
    return new AccountDTO(
        id,
        username,
        passwordHash,
        nickname,
        email,
        role,
        dataScope,
        status,
        time,
        createTime,
        credentialVersion,
        tenantId,
        tenantName,
        tenantEnabled);
  }
}
