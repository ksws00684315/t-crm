package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator-only profile projection and field-whitelisted edits behind /kol-api/me (task 64). The KOL
 * id and tenant always come from the session, never from the request. Validation keeps the
 * registration pairing rule (platform and handle are set or cleared together) and the field length
 * bounds; the platform value itself stays free-form so profiles saved from the back office keep
 * passing validation.
 */
@Service
public class KolPortalProfileService {
  private final KolMapper kols;
  private final CurrentKolContext current;
  private final ExternalAuditApi audit;

  public KolPortalProfileService(
      KolMapper kols, CurrentKolContext current, ExternalAuditApi audit) {
    this.kols = kols;
    this.current = current;
    this.audit = audit;
  }

  public KolPortalProfileDTO get() {
    KolAccountDTO account = current.kol();
    KolDO kol = requireOwnRow(account, false);
    SocialAccountDTO primary =
        SocialAccountsJson.read(kol.getSocialAccounts()).stream()
            .filter(SocialAccountDTO::primary)
            .findFirst()
            .orElse(null);
    return new KolPortalProfileDTO(
        kol.getKolCode(),
        primary == null ? null : primary.platform(),
        primary == null ? null : primary.handle(),
        kol.getReviewStatus(),
        loginStatus(kol),
        kol.getRelationStatus(),
        null,
        null);
  }

  /** Only the nickname and the existing primary social-account slot can change here. */
  @Transactional
  public void update(String name, String primaryPlatform, String primaryHandle, String clientIp) {
    KolAccountDTO account = current.kol();
    String platform = trimToNull(primaryPlatform);
    String handle = trimToNull(primaryHandle);
    if (name == null
        || name.isBlank()
        || KolRules.length(name.strip()) > KolRules.MAX_NAME
        || (platform == null) != (handle == null)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (platform != null
        && (KolRules.length(platform) > KolRules.MAX_PLATFORM
            || KolRules.length(handle) > KolRules.MAX_HANDLE)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolDO kol = requireOwnRow(account, true);
    List<SocialAccountDTO> socialAccounts =
        new ArrayList<>(
            SocialAccountsJson.read(kol.getSocialAccounts()).stream()
                .filter(item -> !item.primary())
                .toList());
    if (platform != null) {
      socialAccounts.add(new SocialAccountDTO(platform, handle, null, null, true));
    }
    var update =
        new LambdaUpdateWrapper<KolDO>()
            .eq(KolDO::getId, account.id())
            .eq(KolDO::getTenantId, account.tenantId())
            .set(KolDO::getName, name.strip())
            .set(
                KolDO::getSocialAccounts,
                SocialAccountsJson.write(socialAccounts),
                "typeHandler=com.tcrm.admin.kol.model.JsonbStringTypeHandler")
            .set(KolDO::getUpdateTime, Instant.now());
    if (kols.update(null, update) != 1) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    audit.recordKolOperation(
        account.tenantId(),
        account.email(),
        "KolProfile",
        "Update profile",
        "/kol-api/me",
        clientIp,
        "{\"kolId\":\"" + account.id() + "\"}");
  }

  private KolDO requireOwnRow(KolAccountDTO account, boolean lock) {
    if (lock && kols.lockById(account.id()) == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    KolDO kol = kols.selectById(account.id());
    if (kol == null || !account.tenantId().equals(kol.getTenantId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return kol;
  }

  private static String loginStatus(KolDO kol) {
    if (Integer.valueOf(0).equals(kol.getStatus())) {
      return "FROZEN";
    }
    return Integer.valueOf(1).equals(kol.getAccountStatus()) ? "NORMAL" : "ABNORMAL";
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
