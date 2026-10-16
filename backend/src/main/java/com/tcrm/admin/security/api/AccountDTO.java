package com.tcrm.admin.security.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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
    @JsonIgnore boolean tenantEnabled,
    Set<String> permissions,
    PostTemplate postTemplate) {

  public AccountDTO {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  /**
   * Snapshot without stored permissions. Callers that authorize a request must use the constructor
   * that carries the granted codes.
   */
  public AccountDTO(
      Long id,
      String username,
      String passwordHash,
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
      boolean tenantEnabled) {
    this(
        id,
        username,
        passwordHash,
        nickname,
        email,
        role,
        dataScope,
        status,
        lastLoginTime,
        createTime,
        credentialVersion,
        tenantId,
        tenantName,
        tenantEnabled,
        Set.of(),
        null);
  }

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
        tenantEnabled,
        permissions,
        postTemplate);
  }

  /** True when this staff account is limited to KOLs assigned to them. */
  public boolean ownScope() {
    return role == Role.STAFF && dataScope == DataScope.OWN;
  }

  /**
   * Codes this account may use. A super administrator has every code; a platform administrator has
   * none; a staff account has the codes stored for them. Order matches {@link Permission}.
   */
  public List<String> effectivePermissions() {
    if (role == Role.SUPER_ADMIN) {
      return Permission.codes();
    }
    if (role != Role.STAFF || permissions.isEmpty()) {
      return List.of();
    }
    return Permission.codes().stream().filter(permissions::contains).toList();
  }
}
