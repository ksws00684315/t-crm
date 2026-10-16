package com.tcrm.admin.kol.service;

import static com.tcrm.admin.kol.service.KolImportColumn.BD_USERNAME;
import static com.tcrm.admin.kol.service.KolImportColumn.CODE;
import static com.tcrm.admin.kol.service.KolImportColumn.COUNTRY;
import static com.tcrm.admin.kol.service.KolImportColumn.DEFAULT_RATE;
import static com.tcrm.admin.kol.service.KolImportColumn.EMAIL;
import static com.tcrm.admin.kol.service.KolImportColumn.FOLLOWERS;
import static com.tcrm.admin.kol.service.KolImportColumn.HANDLE;
import static com.tcrm.admin.kol.service.KolImportColumn.INVITE_CODE;
import static com.tcrm.admin.kol.service.KolImportColumn.NAME;
import static com.tcrm.admin.kol.service.KolImportColumn.PLATFORM;
import static com.tcrm.admin.kol.service.KolImportColumn.REMARK;
import static com.tcrm.admin.kol.service.KolImportColumn.SHEET_URL;
import static com.tcrm.admin.kol.service.KolImportColumn.TEMPLATE_CODE;
import static com.tcrm.admin.kol.service.KolImportColumn.WHATSAPP;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportRowProcessor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Imports KOL rows (task 04 §4.5; task 11 §4.3 added the four profile columns): insert only, one
 * transaction per row. Reasons are message keys (rendered per task language) in column order; a
 * value repeated in the file fails on its second and later occurrences. Rows from a pre-task-11
 * file simply have empty cells for the new columns.
 */
class KolImportProcessor implements ImportRowProcessor {
  static final String CODE_FORMAT = "import.kol.error.code-format";
  static final String CODE_DUPLICATE = "import.kol.error.code-duplicate";
  static final String CODE_EXISTS = "import.kol.error.code-exists";
  static final String NAME_REQUIRED = "import.kol.error.name-required";
  static final String NAME_LENGTH = "import.kol.error.name-length";
  static final String EMAIL_REQUIRED = "import.kol.error.email-required";
  static final String EMAIL_LENGTH = "import.kol.error.email-length";
  static final String EMAIL_FORMAT = "import.kol.error.email-format";
  static final String EMAIL_DUPLICATE = "import.kol.error.email-duplicate";
  static final String EMAIL_EXISTS = "import.kol.error.email-exists";
  static final String COUNTRY_FORMAT = "import.kol.error.country-format";
  static final String BD_INVALID = "import.kol.error.bd-invalid";
  static final String RATE_FORMAT = "import.kol.error.rate-format";
  static final String PLATFORM_LENGTH = "import.kol.error.platform-length";
  static final String HANDLE_LENGTH = "import.kol.error.handle-length";
  static final String SOCIAL_PAIR = "import.kol.error.social-pair";
  static final String FOLLOWERS_FORMAT = "import.kol.error.followers-format";
  static final String FOLLOWERS_WITHOUT_HANDLE = "import.kol.error.followers-without-handle";
  static final String REMARK_LENGTH = "import.kol.error.remark-length";
  static final String WHATSAPP_FORMAT = "import.kol.error.whatsapp-format";
  static final String TEMPLATE_INVALID = "import.kol.error.template-invalid";
  static final String INVITE_CODE_FORMAT = "import.kol.error.invite-code-format";
  static final String SHEET_URL_FORMAT = "import.kol.error.sheet-url-format";

  private final ImportActor actor;
  private final KolMapper kols;
  private final KolWriter writer;
  private final BdUserApi bdUsers;
  private final LinkTemplateService templates;
  private final Set<String> seenCodes = new HashSet<>();
  private final Set<String> seenEmails = new HashSet<>();

  KolImportProcessor(
      ImportActor actor,
      KolMapper kols,
      KolWriter writer,
      BdUserApi bdUsers,
      LinkTemplateService templates) {
    this.actor = actor;
    this.kols = kols;
    this.writer = writer;
    this.bdUsers = bdUsers;
    this.templates = templates;
  }

  @Override
  public List<ImportReason> process(List<String> row) {
    List<ImportReason> errors = new ArrayList<>();
    String code = code(CODE.of(row).toUpperCase(Locale.ROOT), errors);
    String name = NAME.of(row);
    if (name.isEmpty()) {
      errors.add(ImportReason.of(NAME_REQUIRED));
    } else if (KolRules.length(name) > KolRules.MAX_NAME) {
      errors.add(ImportReason.of(NAME_LENGTH));
    }
    String email = email(EMAIL.of(row).toLowerCase(Locale.ROOT), errors);
    String country = country(COUNTRY.of(row), errors);
    Long bd = bd(BD_USERNAME.of(row), errors);
    BigDecimal rate = null;
    if (!DEFAULT_RATE.of(row).isEmpty()) {
      rate = KolRules.percentToRate(DEFAULT_RATE.of(row));
      if (rate == null) {
        errors.add(ImportReason.of(RATE_FORMAT));
      }
    }
    List<SocialAccountDTO> social = social(row, errors);
    String remark = REMARK.of(row);
    if (KolRules.length(remark) > KolRules.MAX_REMARK) {
      errors.add(ImportReason.of(REMARK_LENGTH));
    }
    String whatsapp = WHATSAPP.of(row);
    if (!whatsapp.isEmpty() && !whatsapp.matches(KolRules.WHATSAPP_PATTERN)) {
      errors.add(ImportReason.of(WHATSAPP_FORMAT));
    }
    Long templateId = templateId(TEMPLATE_CODE.of(row), errors);
    String inviteCode = INVITE_CODE.of(row);
    if (!inviteCode.isEmpty() && !inviteCode.matches(KolRules.INVITE_CODE_PATTERN)) {
      errors.add(ImportReason.of(INVITE_CODE_FORMAT));
    }
    String sheetUrl = SHEET_URL.of(row);
    if (!sheetUrl.isEmpty() && !sheetUrl.matches(KolRules.SHEET_URL_PATTERN)) {
      errors.add(ImportReason.of(SHEET_URL_FORMAT));
    }
    if (!errors.isEmpty()) {
      return errors;
    }
    KolDO kol = new KolDO();
    kol.setName(name);
    kol.setEmail(email);
    kol.setCountry(country.isEmpty() ? null : country);
    kol.setSocialAccounts(SocialAccountsJson.write(social));
    kol.setBdUserId(bd);
    kol.setDefaultRate(rate);
    kol.setStatus(1);
    kol.setRemark(remark.isEmpty() ? null : remark);
    kol.setWhatsapp(whatsapp.isEmpty() ? null : whatsapp);
    kol.setLinkTemplateId(templateId);
    kol.setInviteCode(inviteCode.isEmpty() ? null : inviteCode);
    kol.setSheetUrl(sheetUrl.isEmpty() ? null : sheetUrl);
    kol.setRegisterSource(1);
    try {
      writer.insert(kol, code, actor.id());
    } catch (BusinessException e) {
      return List.of(
          switch (e.getErrorCode()) {
            case KOL_EMAIL_EXISTS -> ImportReason.of(EMAIL_EXISTS);
            case KOL_CODE_EXISTS -> ImportReason.of(CODE_EXISTS);
            default -> throw e;
          });
    }
    return List.of();
  }

  private String code(String code, List<ImportReason> errors) {
    if (code.isEmpty()) {
      return null;
    }
    if (!KolRules.isCode(code)) {
      errors.add(ImportReason.of(CODE_FORMAT));
    } else if (!seenCodes.add(code)) {
      errors.add(ImportReason.of(CODE_DUPLICATE));
    } else if (TenantScope.callSystem("kol code uniqueness", () -> kols.existsCode(code))) {
      errors.add(ImportReason.of(CODE_EXISTS));
    }
    return code;
  }

  private String email(String email, List<ImportReason> errors) {
    if (email.isEmpty()) {
      errors.add(ImportReason.of(EMAIL_REQUIRED));
    } else if (KolRules.length(email) > KolRules.MAX_EMAIL) {
      errors.add(ImportReason.of(EMAIL_LENGTH));
    } else if (!KolRules.isEmail(email)) {
      errors.add(ImportReason.of(EMAIL_FORMAT));
    } else if (!seenEmails.add(email)) {
      errors.add(ImportReason.of(EMAIL_DUPLICATE));
    } else if (kols.selectCount(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))
        > 0) {
      errors.add(ImportReason.of(EMAIL_EXISTS));
    }
    return email;
  }

  /** Optional since task 11 (self-registration may skip it); two letters when given. */
  private static String country(String raw, List<ImportReason> errors) {
    String country = raw.toUpperCase(Locale.ROOT);
    if (!country.isEmpty() && (country.length() != 2 || !KolRules.isCountry(country))) {
      errors.add(ImportReason.of(COUNTRY_FORMAT));
    }
    return country;
  }

  /** A BD's own imports are always assigned to them; the column is then ignored (§4.2). */
  private Long bd(String username, List<ImportReason> errors) {
    if (actor.own()) {
      return actor.id();
    }
    if (username.isEmpty()) {
      return null;
    }
    BdUserDTO bd = bdUsers.getByUsername(username);
    if (bd == null || !bd.usable()) {
      errors.add(ImportReason.of(BD_INVALID));
      return null;
    }
    return bd.id();
  }

  /** Template id for the agent template code; empty clears, unknown or disabled fails the row. */
  private Long templateId(String code, List<ImportReason> errors) {
    if (code.isEmpty()) {
      return null;
    }
    LinkTemplateDTO template = templates.usableTemplateByCode(code);
    if (template == null) {
      errors.add(ImportReason.of(TEMPLATE_INVALID));
      return null;
    }
    return template.id();
  }

  private static List<SocialAccountDTO> social(List<String> row, List<ImportReason> errors) {
    String platform = PLATFORM.of(row);
    String handle = HANDLE.of(row);
    String followersText = FOLLOWERS.of(row);
    if (KolRules.length(platform) > KolRules.MAX_PLATFORM) {
      errors.add(ImportReason.of(PLATFORM_LENGTH));
    }
    if (KolRules.length(handle) > KolRules.MAX_HANDLE) {
      errors.add(ImportReason.of(HANDLE_LENGTH));
    }
    if (platform.isEmpty() != handle.isEmpty()) {
      errors.add(ImportReason.of(SOCIAL_PAIR));
    }
    Long followers = null;
    if (!followersText.isEmpty()) {
      followers = KolRules.parseCount(followersText);
      if (followers == null) {
        errors.add(ImportReason.of(FOLLOWERS_FORMAT));
      } else if (handle.isEmpty()) {
        errors.add(ImportReason.of(FOLLOWERS_WITHOUT_HANDLE));
      }
    }
    if (platform.isEmpty() || handle.isEmpty()) {
      return List.of();
    }
    return List.of(new SocialAccountDTO(platform, handle, null, followers));
  }
}
