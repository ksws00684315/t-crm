package com.tcrm.admin.security.api;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the current KOL from the login state. Each request re-reads the KOL's status, account
 * status and credential version together with the owning company's status, mirroring the
 * administrator context (design §3.4); the snapshot is loaded once per request.
 */
@Component
public class CurrentKolContext {
  /** Token-session attribute holding the credential version the token was issued for. */
  public static final String CREDENTIAL_VERSION = "kolCredentialVersion";

  private static final String CACHE = CurrentKolContext.class.getName() + ".accounts";
  private static final String VERIFIED = CurrentKolContext.class.getName() + ".verified";
  private static final int SCOPE = RequestAttributes.SCOPE_REQUEST;
  private final KolAccountStore accounts;

  public CurrentKolContext(KolAccountStore accounts) {
    this.accounts = accounts;
  }

  /**
   * Returns the current activated account of an enabled KOL in an enabled company; throws
   * NotLoginException, AUTH_ACCOUNT_DISABLED, or AUTH_UNAUTHORIZED when the token was issued for an
   * older credential version (the password changed since). Within one HTTP request the account is
   * loaded and checked once.
   */
  public KolAccountDTO kol() {
    Long id = StpKolUtil.getLoginIdAsLong();
    KolAccountDTO kol = load(id);
    if (kol == null || !kol.enabled() || !kol.activated() || !kol.tenantEnabled()) {
      StpKolUtil.kickout(id);
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    requireCurrentCredentials(kol);
    return kol;
  }

  /**
   * Rejects a token bound to another credential version, or to none. Like the administrator side
   * this also covers a password change whose Redis logout failed and a login that raced with a
   * reset: such tokens carry the old version.
   */
  private void requireCurrentCredentials(KolAccountDTO kol) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null && Boolean.TRUE.equals(request.getAttribute(VERIFIED, SCOPE))) {
      return;
    }
    Object bound = StpKolUtil.getTokenSession().get(CREDENTIAL_VERSION);
    if (!(bound instanceof Number version) || version.intValue() != kol.credentialVersion()) {
      StpKolUtil.logoutByTokenValue(StpKolUtil.getTokenValue());
      throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
    }
    if (request != null) {
      request.setAttribute(VERIFIED, Boolean.TRUE, SCOPE);
    }
  }

  @SuppressWarnings("unchecked")
  private KolAccountDTO load(Long id) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request == null) {
      return accounts.getById(id);
    }
    Map<Long, KolAccountDTO> cache = (Map<Long, KolAccountDTO>) request.getAttribute(CACHE, SCOPE);
    if (cache == null) {
      cache = new HashMap<>();
      request.setAttribute(CACHE, cache, SCOPE);
    }
    KolAccountDTO kol = cache.get(id);
    if (kol == null) {
      kol = accounts.getById(id);
      if (kol != null) {
        cache.put(id, kol);
      }
    }
    return kol;
  }
}
