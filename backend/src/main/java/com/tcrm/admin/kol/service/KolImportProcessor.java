package com.tcrm.admin.kol.service;

import static com.tcrm.admin.kol.service.KolImportColumn.BD_USERNAME;
import static com.tcrm.admin.kol.service.KolImportColumn.CODE;
import static com.tcrm.admin.kol.service.KolImportColumn.COUNTRY;
import static com.tcrm.admin.kol.service.KolImportColumn.DEFAULT_RATE;
import static com.tcrm.admin.kol.service.KolImportColumn.EMAIL;
import static com.tcrm.admin.kol.service.KolImportColumn.FOLLOWERS;
import static com.tcrm.admin.kol.service.KolImportColumn.HANDLE;
import static com.tcrm.admin.kol.service.KolImportColumn.NAME;
import static com.tcrm.admin.kol.service.KolImportColumn.PLATFORM;
import static com.tcrm.admin.kol.service.KolImportColumn.REMARK;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.task.api.ImportRowProcessor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Imports KOL rows (task 04 §4.5): insert only, one transaction per row. Reasons are Chinese and in
 * column order; a value repeated in the file fails on its second and later occurrences.
 */
class KolImportProcessor implements ImportRowProcessor {
  static final String CODE_FORMAT = "达人编号格式不正确，应为 KOL 加 4～8 位数字";
  static final String CODE_DUPLICATE = "达人编号在文件中重复";
  static final String CODE_EXISTS = "达人编号已存在";
  static final String NAME_REQUIRED = "姓名不能为空";
  static final String NAME_LENGTH = "姓名不能超过 64 个字符";
  static final String EMAIL_REQUIRED = "邮箱不能为空";
  static final String EMAIL_LENGTH = "邮箱不能超过 128 个字符";
  static final String EMAIL_FORMAT = "邮箱格式不正确";
  static final String EMAIL_DUPLICATE = "邮箱在文件中重复";
  static final String EMAIL_EXISTS = "邮箱已存在";
  static final String COUNTRY_REQUIRED = "国家不能为空";
  static final String COUNTRY_FORMAT = "国家应为 ISO 3166-1 两位字母代码，如 US";
  static final String BD_INVALID = "负责商务不存在或不是启用的商务账号";
  static final String RATE_FORMAT = "默认佣金比例应为 0～100 的数字，最多 2 位小数";
  static final String PLATFORM_LENGTH = "社媒平台不能超过 32 个字符";
  static final String HANDLE_LENGTH = "社媒账号不能超过 128 个字符";
  static final String SOCIAL_PAIR = "社媒平台与社媒账号须同时填写或同时为空";
  static final String FOLLOWERS_FORMAT = "粉丝数应为非负整数";
  static final String FOLLOWERS_WITHOUT_HANDLE = "填写粉丝数时须填写社媒账号";
  static final String REMARK_LENGTH = "备注不能超过 500 个字符";

  private final ImportActor actor;
  private final KolMapper kols;
  private final KolWriter writer;
  private final BdUserApi bdUsers;
  private final Set<String> seenCodes = new HashSet<>();
  private final Set<String> seenEmails = new HashSet<>();

  KolImportProcessor(ImportActor actor, KolMapper kols, KolWriter writer, BdUserApi bdUsers) {
    this.actor = actor;
    this.kols = kols;
    this.writer = writer;
    this.bdUsers = bdUsers;
  }

  @Override
  public List<String> process(List<String> row) {
    List<String> errors = new ArrayList<>();
    String code = code(CODE.of(row).toUpperCase(Locale.ROOT), errors);
    String name = NAME.of(row);
    if (name.isEmpty()) {
      errors.add(NAME_REQUIRED);
    } else if (KolRules.length(name) > KolRules.MAX_NAME) {
      errors.add(NAME_LENGTH);
    }
    String email = email(EMAIL.of(row).toLowerCase(Locale.ROOT), errors);
    String country = COUNTRY.of(row).toUpperCase(Locale.ROOT);
    if (country.isEmpty()) {
      errors.add(COUNTRY_REQUIRED);
    } else if (country.length() != 2 || !KolRules.isCountry(country)) {
      errors.add(COUNTRY_FORMAT);
    }
    Long bd = bd(BD_USERNAME.of(row), errors);
    BigDecimal rate = null;
    if (!DEFAULT_RATE.of(row).isEmpty()) {
      rate = KolRules.percentToRate(DEFAULT_RATE.of(row));
      if (rate == null) {
        errors.add(RATE_FORMAT);
      }
    }
    List<SocialAccountDTO> social = social(row, errors);
    String remark = REMARK.of(row);
    if (KolRules.length(remark) > KolRules.MAX_REMARK) {
      errors.add(REMARK_LENGTH);
    }
    if (!errors.isEmpty()) {
      return errors;
    }
    KolDO kol = new KolDO();
    kol.setName(name);
    kol.setEmail(email);
    kol.setCountry(country);
    kol.setSocialAccounts(SocialAccountsJson.write(social));
    kol.setBdUserId(bd);
    kol.setDefaultRate(rate);
    kol.setStatus(1);
    kol.setRemark(remark.isEmpty() ? null : remark);
    try {
      writer.insert(kol, code, actor.id());
    } catch (BusinessException e) {
      return List.of(
          switch (e.getErrorCode()) {
            case KOL_EMAIL_EXISTS -> EMAIL_EXISTS;
            case KOL_CODE_EXISTS -> CODE_EXISTS;
            default -> throw e;
          });
    }
    return List.of();
  }

  private String code(String code, List<String> errors) {
    if (code.isEmpty()) {
      return null;
    }
    if (!KolRules.isCode(code)) {
      errors.add(CODE_FORMAT);
    } else if (!seenCodes.add(code)) {
      errors.add(CODE_DUPLICATE);
    } else if (kols.existsCode(code)) {
      errors.add(CODE_EXISTS);
    }
    return code;
  }

  private String email(String email, List<String> errors) {
    if (email.isEmpty()) {
      errors.add(EMAIL_REQUIRED);
    } else if (KolRules.length(email) > KolRules.MAX_EMAIL) {
      errors.add(EMAIL_LENGTH);
    } else if (!KolRules.isEmail(email)) {
      errors.add(EMAIL_FORMAT);
    } else if (!seenEmails.add(email)) {
      errors.add(EMAIL_DUPLICATE);
    } else if (kols.selectCount(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))
        > 0) {
      errors.add(EMAIL_EXISTS);
    }
    return email;
  }

  /** A BD's own imports are always assigned to them; the column is then ignored (§4.2). */
  private Long bd(String username, List<String> errors) {
    if (actor.own()) {
      return actor.id();
    }
    if (username.isEmpty()) {
      return null;
    }
    BdUserDTO bd = bdUsers.getByUsername(username);
    if (bd == null || !bd.usable()) {
      errors.add(BD_INVALID);
      return null;
    }
    return bd.id();
  }

  private static List<SocialAccountDTO> social(List<String> row, List<String> errors) {
    String platform = PLATFORM.of(row);
    String handle = HANDLE.of(row);
    String followersText = FOLLOWERS.of(row);
    if (KolRules.length(platform) > KolRules.MAX_PLATFORM) {
      errors.add(PLATFORM_LENGTH);
    }
    if (KolRules.length(handle) > KolRules.MAX_HANDLE) {
      errors.add(HANDLE_LENGTH);
    }
    if (platform.isEmpty() != handle.isEmpty()) {
      errors.add(SOCIAL_PAIR);
    }
    Long followers = null;
    if (!followersText.isEmpty()) {
      followers = KolRules.parseCount(followersText);
      if (followers == null) {
        errors.add(FOLLOWERS_FORMAT);
      } else if (handle.isEmpty()) {
        errors.add(FOLLOWERS_WITHOUT_HANDLE);
      }
    }
    if (platform.isEmpty() || handle.isEmpty()) {
      return List.of();
    }
    return List.of(new SocialAccountDTO(platform, handle, null, followers));
  }
}
