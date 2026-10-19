package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolRegisterCodeMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolRegisterCodeDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.kol.service.KolAuthService.LoginResult;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.api.TenantDirectoryApi;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Public self-registration (task 12c / 29). The register code selects the company and the owning
 * BD; the new KOL is activated at once and signed in with the same payload as a password login.
 */
@Service
public class KolSelfRegisterService {
  private static final Pattern CODE = Pattern.compile("[2-9A-HJ-NP-Z]{8}");

  private final KolRegisterCodeMapper codes;
  private final BdUserApi bdUsers;
  private final TenantDirectoryApi tenants;
  private final KolMapper kols;
  private final KolWriter writer;
  private final BCryptPasswordEncoder passwords;
  private final KolEmailCodes emailCodes;
  private final KolMails mails;
  private final ExternalAuditApi audit;
  private final KolAuthService auth;

  public KolSelfRegisterService(
      KolRegisterCodeMapper codes,
      BdUserApi bdUsers,
      TenantDirectoryApi tenants,
      KolMapper kols,
      KolWriter writer,
      BCryptPasswordEncoder passwords,
      KolEmailCodes emailCodes,
      KolMails mails,
      ExternalAuditApi audit,
      KolAuthService auth) {
    this.codes = codes;
    this.bdUsers = bdUsers;
    this.tenants = tenants;
    this.kols = kols;
    this.writer = writer;
    this.passwords = passwords;
    this.emailCodes = emailCodes;
    this.mails = mails;
    this.audit = audit;
    this.auth = auth;
  }

  /** BD display name for a link that is still usable; anything else is a 404. */
  public KolRegisterPreview preview(String rawCode, String clientIp) {
    emailCodes.requirePreviewIp(clientIp);
    return new KolRegisterPreview(requireLink(rawCode).bdUserName());
  }

  public void sendEmailCode(String rawCode, String email, String clientIp) {
    emailCodes.requireSendIp(clientIp);
    Resolved link = requireLink(rawCode);
    String address = normalize(email);
    if (emailTaken(link.tenantId(), address)) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
    }
    emailCodes.reserveSend(link.tenantId(), address);
    String code = emailCodes.issue(link.tenantId(), address);
    mails.sendRegisterCode(address, link.tenantName(), code);
  }

  public LoginResult register(KolRegistration request, String clientIp) {
    emailCodes.requireRegisterIp(clientIp);
    String address = normalize(request.email());
    emailCodes.requireRegisterEmail(address);
    validateSocialPair(request.primaryPlatform(), request.primaryHandle());
    Resolved link = requireLink(request.code());
    if (emailTaken(link.tenantId(), address)) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
    }
    emailCodes.consume(link.tenantId(), address, request.emailCode());
    KolDO kol = TenantScope.callCompany(link.tenantId(), () -> insert(link, request, address));
    audit.recordKolOperation(
        link.tenantId(),
        address,
        "KolRegister",
        "Self register",
        "/kol-api/register",
        clientIp,
        "{\"kolId\":\"" + kol.getId() + "\"}");
    return auth.loginEstablished(kol.getId());
  }

  private KolDO insert(Resolved link, KolRegistration request, String address) {
    String name = request.name().strip();
    List<SocialAccountDTO> socials = List.of();
    if (request.primaryPlatform() != null && !request.primaryPlatform().isBlank()) {
      socials =
          List.of(
              new SocialAccountDTO(
                  request.primaryPlatform(),
                  request.primaryHandle().strip(),
                  blankToNull(request.primaryUrl()),
                  null,
                  true));
    }
    KolDO kol = new KolDO();
    kol.setName(name);
    kol.setEmail(address);
    kol.setPhone(blankToNull(request.phone()));
    kol.setCountry(country(request.country()));
    kol.setSocialAccounts(SocialAccountsJson.write(socials));
    kol.setBdUserId(link.bdUserId());
    kol.setStatus(1);
    kol.setWhatsapp(blankToNull(request.whatsapp()));
    kol.setRegisterSource(2);
    kol.setReviewStatus(ReviewStatus.PENDING.name());
    kol.setRelationStatus(RelationStatus.PENDING_PROMOTION.name());
    kol.setAccountStatus(1);
    kol.setPasswordHash(passwords.encode(request.password()));
    kol.setCredentialVersion(0);
    return writer.insert(kol, null, null);
  }

  private boolean emailTaken(long tenantId, String email) {
    return TenantScope.callCompany(
        tenantId,
        () ->
            kols.selectCount(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))
                > 0);
  }

  private Resolved requireLink(String rawCode) {
    String code = KolRegisterCodeGenerator.normalize(rawCode);
    if (code == null || !CODE.matcher(code).matches()) {
      throw new BusinessException(ErrorCode.KOL_REGISTER_CODE_INVALID);
    }
    return TenantScope.callSystem(
        "resolve a KOL register code",
        () -> {
          KolRegisterCodeDO row =
              codes.selectOne(
                  new LambdaQueryWrapper<KolRegisterCodeDO>().eq(KolRegisterCodeDO::getCode, code));
          if (row == null
              || !Integer.valueOf(KolRegisterCodeDO.STATUS_ACTIVE).equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.KOL_REGISTER_CODE_INVALID);
          }
          BdUserDTO bd =
              TenantScope.callCompany(row.getTenantId(), () -> bdUsers.getById(row.getBdUserId()));
          if (bd == null || !bd.usable()) {
            throw new BusinessException(ErrorCode.KOL_REGISTER_CODE_INVALID);
          }
          var tenant =
              tenants.listByIds(List.of(row.getTenantId())).stream().findFirst().orElse(null);
          if (tenant == null || !tenant.enabled()) {
            throw new BusinessException(ErrorCode.KOL_REGISTER_CODE_INVALID);
          }
          String bdName =
              bd.nickname() == null || bd.nickname().isBlank() ? bd.username() : bd.nickname();
          return new Resolved(row.getTenantId(), row.getBdUserId(), tenant.name(), bdName);
        });
  }

  private static void validateSocialPair(String platform, String handle) {
    boolean hasPlatform = platform != null && !platform.isBlank();
    boolean hasHandle = handle != null && !handle.isBlank();
    if (hasPlatform != hasHandle) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  private static String country(String value) {
    return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private record Resolved(long tenantId, long bdUserId, String tenantName, String bdUserName) {}
}
