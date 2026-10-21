package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolLinkTargetChangedEvent;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPlatformMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPlatformDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.kol.model.PromotionChangeType;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.kol.service.KolPromotionSupport.PlatformRef;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Review, relation status, promotion config and versioned records (task 29 / ADR-025). */
@Service
public class KolPromotionService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_ADDRESS = 512;
  private static final int MAX_REMARK = 500;

  private final KolMapper kols;
  private final KolPlatformMapper platforms;
  private final KolPromotionRecordMapper records;
  private final LinkTemplateService templates;
  private final BdUserApi bdUsers;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final ApplicationEventPublisher events;

  public KolPromotionService(
      KolMapper kols,
      KolPlatformMapper platforms,
      KolPromotionRecordMapper records,
      LinkTemplateService templates,
      BdUserApi bdUsers,
      CurrentUserContext current,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events) {
    this.kols = kols;
    this.platforms = platforms;
    this.records = records;
    this.templates = templates;
    this.bdUsers = bdUsers;
    this.current = current;
    this.tx = tx;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.events = events;
  }

  public PromotionConfigDTO getConfig(Long id) {
    KolDO kol = requireOwned(id, current.user());
    return toConfig(kol, platformRefs(id));
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateConfig(Long id, PromotionConfigCommand command) {
    AccountDTO user = current.user();
    tx.executeWithoutResult(
        status -> {
          KolDO kol = requireOwned(id, user);
          if (!ReviewStatus.APPROVED.name().equals(kol.getReviewStatus())) {
            throw new BusinessException(ErrorCode.KOL_REVIEW_REQUIRED);
          }
          List<PlatformRef> beforePlatforms = platformRefs(id);
          Map<String, Object> before = KolPromotionSupport.snapshot(kol, beforePlatforms);
          String beforeInvite = kol.getInviteCode();
          Long beforeJump = kol.getLinkTemplateId();
          Instant now = Instant.now();
          int nextVersion = (kol.getConfigVersion() == null ? 0 : kol.getConfigVersion()) + 1;
          applyConfig(kol, command, user.id(), nextVersion, now);
          List<PlatformRef> afterPlatforms = platformRefs(id);
          KolDO after = kols.selectById(id);
          Map<String, Object> afterSnap = KolPromotionSupport.snapshot(after, afterPlatforms);
          insertConfigRecord(
              id, before, afterSnap, blankToNull(command.remark()), user.id(), nextVersion, now);
          boolean targetChanged =
              !Objects.equals(beforeInvite, after.getInviteCode())
                  || !Objects.equals(beforeJump, after.getLinkTemplateId());
          if (targetChanged) {
            TransactionalEvents.publish(events, new KolLinkTargetChangedEvent(id));
          }
          recordAudit("Save promotion config", Map.of("kolId", id, "version", nextVersion));
        });
  }

  @Transactional(rollbackFor = Exception.class)
  public void review(Long id, ReviewCommand command) {
    AccountDTO user = current.user();
    tx.executeWithoutResult(
        txStatus -> {
          KolDO kol = requireProfileVisible(id, user);
          if (!ReviewStatus.PENDING.name().equals(kol.getReviewStatus())) {
            throw new BusinessException(ErrorCode.KOL_REVIEW_ALREADY_DONE);
          }
          Map<String, Object> before = KolPromotionSupport.profileSnapshot(kol);
          String social =
              SocialAccountsJson.write(
                  primarySocial(command.primaryPlatform(), command.primaryHandle(), kol));
          Instant now = Instant.now();
          int updated =
              kols.update(
                  null,
                  scopedProfile(id, user)
                      .set(KolDO::getName, command.name().strip())
                      .set(KolDO::getPhone, blankToNull(command.phone()))
                      .set(KolDO::getWhatsapp, blankToNull(command.whatsapp()))
                      .set(KolDO::getCountry, country(command.country()))
                      .set(KolDO::getRemark, blankToNull(command.remark()))
                      .set(
                          KolDO::getSocialAccounts,
                          social,
                          "typeHandler=com.tcrm.admin.kol.model.JsonbStringTypeHandler")
                      .set(KolDO::getReviewStatus, ReviewStatus.APPROVED.name())
                      .set(KolDO::getUpdateBy, user.id())
                      .set(KolDO::getUpdateTime, now));
          requireUpdated(updated, user);
          KolDO after = kols.selectById(id);
          Map<String, Object> afterSnap = KolPromotionSupport.profileSnapshot(after);
          insertRecord(id, PromotionChangeType.REVIEW, before, afterSnap, null, user.id());
          recordAudit("Review KOL", Map.of("kolId", id));
        });
  }

  public PageResult<PromotionRecordDTO> pageRecords(Long id, int page, int size) {
    requireOwned(id, current.user());
    var result =
        records.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<KolPromotionRecordDO>()
                .eq(KolPromotionRecordDO::getKolId, id)
                .orderByDesc(KolPromotionRecordDO::getCreateTime, KolPromotionRecordDO::getId));
    Set<Long> operators =
        result.getRecords().stream()
            .map(KolPromotionRecordDO::getCreateBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> names = new HashMap<>();
    for (BdUserDTO bd : bdUsers.listByIds(operators)) {
      String nickname = bd.nickname();
      names.put(bd.id(), nickname == null || nickname.isBlank() ? bd.username() : nickname);
    }
    return new PageResult<>(
        result.getRecords().stream()
            .map(
                r ->
                    new PromotionRecordDTO(
                        r.getChangeType(),
                        parseJson(r.getBeforeJson()),
                        parseJson(r.getAfterJson()),
                        r.getRemark(),
                        r.getCreateBy() == null ? null : names.get(r.getCreateBy()),
                        r.getCreateTime(),
                        r.getVersionNo(),
                        r.getEffectiveTime()))
            .toList(),
        result.getTotal(),
        page,
        size);
  }

  private void applyConfig(
      KolDO kol, PromotionConfigCommand command, Long actorId, int version, Instant now) {
    BigDecimal rate = parseRate(command.defaultRate());
    String invite = blankToNull(command.inviteCode());
    if (invite == null) {
      invite = kol.getInviteCode();
    }
    if (invite != null && !invite.matches(KolRules.INVITE_CODE_PATTERN)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String sheet = blankToNull(command.sheetUrl());
    if (sheet != null && !sheet.matches(KolRules.SHEET_URL_PATTERN)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<PlatformInput> platformInputs = normalizePlatforms(kol.getId(), command.platforms());
    Long jumpId =
        resolveJumpTemplateId(
            command.jumpTemplateId(),
            platformInputs.stream().map(PlatformInput::templateId).toList());
    AccountDTO user = current.user();
    String remark = blankToNull(command.remark());
    if (rate == null
        || sheet == null
        || platformInputs.isEmpty()
        || platformInputs.stream().anyMatch(p -> p.address() == null)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    int updated =
        kols.update(
            null,
            scoped(kol.getId(), user)
                .set(KolDO::getDefaultRate, rate)
                .set(KolDO::getInviteCode, invite)
                .set(KolDO::getSheetUrl, sheet)
                .set(KolDO::getPromotionRemark, remark)
                .set(KolDO::getLinkTemplateId, jumpId)
                .set(KolDO::getRelationStatus, RelationStatus.PROMOTING.name())
                .set(KolDO::getConfigVersion, version)
                .set(KolDO::getUpdateBy, actorId)
                .set(KolDO::getUpdateTime, now));
    requireUpdated(updated, user);
    replacePlatforms(kol.getId(), platformInputs, actorId);
  }

  private Long resolveJumpTemplateId(Long requested, List<Long> templateIds) {
    if (templateIds.isEmpty()) {
      return null;
    }
    Long resolved = requested == null ? templateIds.get(0) : requested;
    if (!templateIds.contains(resolved)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return resolved;
  }

  List<PlatformInput> normalizePlatforms(Long kolId, List<PlatformInput> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    Set<Long> seen = new HashSet<>();
    List<PlatformInput> out = new ArrayList<>();
    // crm_kol_platform.address is nullable (V202610191002); a null stored address falls back to
    // the link-template URL, so rows without an address are skipped instead of collected —
    // Collectors.toMap rejects null values.
    Map<Long, String> existingAddresses = new HashMap<>();
    for (KolPlatformDO row :
        platforms.selectList(
            new LambdaQueryWrapper<KolPlatformDO>().eq(KolPlatformDO::getKolId, kolId))) {
      if (row.getAddress() != null) {
        existingAddresses.put(row.getLinkTemplateId(), row.getAddress());
      }
    }
    for (PlatformInput p : raw) {
      if (p == null || p.templateId() == null || p.templateId() <= 0 || !seen.add(p.templateId())) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      LinkTemplateDTO template = templates.usableTemplate(p.templateId());
      String requested = blankToNull(p.address());
      String address =
          requireHttps(
              requested != null
                  ? requested
                  : existingAddresses.getOrDefault(p.templateId(), template.templateUrl()));
      out.add(new PlatformInput(p.templateId(), address));
    }
    return out;
  }

  private void replacePlatforms(Long kolId, List<PlatformInput> platformInputs, Long actorId) {
    List<KolPlatformDO> existing =
        platforms.selectList(
            new LambdaQueryWrapper<KolPlatformDO>().eq(KolPlatformDO::getKolId, kolId));
    Set<Long> keep =
        platformInputs.stream().map(PlatformInput::templateId).collect(Collectors.toSet());
    for (KolPlatformDO row : existing) {
      if (!keep.contains(row.getLinkTemplateId())) {
        platforms.deleteById(row.getId());
      }
    }
    Map<Long, KolPlatformDO> byTemplate =
        existing.stream()
            .filter(r -> keep.contains(r.getLinkTemplateId()))
            .collect(Collectors.toMap(KolPlatformDO::getLinkTemplateId, r -> r, (a, b) -> a));
    for (PlatformInput input : platformInputs) {
      KolPlatformDO row = byTemplate.get(input.templateId());
      if (row == null) {
        row = new KolPlatformDO();
        row.setKolId(kolId);
        row.setLinkTemplateId(input.templateId());
        row.setAddress(input.address());
        row.setCreateBy(actorId);
        row.setUpdateBy(actorId);
        row.setDeleted(false);
        platforms.insert(row);
      } else {
        row.setAddress(input.address());
        row.setUpdateBy(actorId);
        row.setUpdateTime(Instant.now());
        platforms.updateById(row);
      }
    }
  }

  private void insertConfigRecord(
      Long kolId,
      Map<String, Object> before,
      Map<String, Object> after,
      String remark,
      Long actorId,
      int version,
      Instant effectiveTime) {
    KolPromotionRecordDO row = new KolPromotionRecordDO();
    row.setKolId(kolId);
    row.setChangeType(PromotionChangeType.CONFIG.name());
    row.setBeforeJson(writeJson(before));
    row.setAfterJson(writeJson(after));
    row.setRemark(remark);
    row.setVersionNo(version);
    row.setEffectiveTime(effectiveTime);
    row.setCreateBy(actorId);
    records.insert(row);
  }

  private void insertRecord(
      Long kolId,
      PromotionChangeType type,
      Map<String, Object> before,
      Map<String, Object> after,
      String remark,
      Long actorId) {
    KolPromotionRecordDO row = new KolPromotionRecordDO();
    row.setKolId(kolId);
    row.setChangeType(type.name());
    row.setBeforeJson(writeJson(before));
    row.setAfterJson(writeJson(after));
    row.setRemark(remark);
    row.setCreateBy(actorId);
    records.insert(row);
  }

  private List<PlatformRef> platformRefs(Long kolId) {
    return platforms
        .selectList(new LambdaQueryWrapper<KolPlatformDO>().eq(KolPlatformDO::getKolId, kolId))
        .stream()
        .map(r -> new PlatformRef(r.getLinkTemplateId(), r.getAddress()))
        .sorted((a, b) -> Long.compare(a.templateId(), b.templateId()))
        .toList();
  }

  private static PromotionConfigDTO toConfig(KolDO kol, List<PlatformRef> platformRefs) {
    return new PromotionConfigDTO(
        KolRules.rateText(kol.getDefaultRate()),
        platformRefs.stream().map(p -> new PlatformInput(p.templateId(), p.address())).toList(),
        kol.getInviteCode(),
        kol.getSheetUrl(),
        kol.getLinkTemplateId(),
        kol.getPromotionRemark());
  }

  private List<SocialAccountDTO> primarySocial(String platform, String handle, KolDO existing) {
    List<SocialAccountDTO> current = SocialAccountsJson.read(existing.getSocialAccounts());
    if (platform == null || platform.isBlank()) {
      if (handle != null && !handle.isBlank()) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      return current;
    }
    if (handle == null || handle.isBlank()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<SocialAccountDTO> out = new ArrayList<>();
    out.add(new SocialAccountDTO(platform, handle.strip(), null, null, true));
    for (SocialAccountDTO a : current) {
      if (!a.primary()) {
        out.add(a);
      }
    }
    return out;
  }

  private KolDO requireOwned(Long id, AccountDTO user) {
    KolDO kol = kols.selectById(id);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope() && !user.id().equals(kol.getBdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return kol;
  }

  private KolDO requireProfileVisible(Long id, AccountDTO user) {
    KolDO kol = kols.selectById(id);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope() && kol.getBdUserId() != null && !user.id().equals(kol.getBdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return kol;
  }

  private static LambdaUpdateWrapper<KolDO> scoped(Long id, AccountDTO user) {
    return new LambdaUpdateWrapper<KolDO>()
        .eq(KolDO::getId, id)
        .eq(user.ownScope(), KolDO::getBdUserId, user.id());
  }

  private static LambdaUpdateWrapper<KolDO> scopedProfile(Long id, AccountDTO user) {
    return new LambdaUpdateWrapper<KolDO>()
        .eq(KolDO::getId, id)
        .and(
            user.ownScope(),
            w -> w.eq(KolDO::getBdUserId, user.id()).or().isNull(KolDO::getBdUserId));
  }

  private static void requireUpdated(int updated, AccountDTO user) {
    if (updated == 0) {
      throw new BusinessException(
          user.ownScope() ? ErrorCode.AUTH_FORBIDDEN : ErrorCode.COMMON_NOT_FOUND);
    }
  }

  private static BigDecimal parseRate(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String value = text.strip();
    if (value.contains("%")) {
      BigDecimal rate = KolRules.percentToRate(value);
      if (rate == null) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      return rate;
    }
    try {
      BigDecimal rate = new BigDecimal(value);
      if (rate.compareTo(BigDecimal.ZERO) < 0) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      if (rate.compareTo(BigDecimal.ONE) > 0) {
        BigDecimal asPercent = KolRules.percentToRate(value);
        if (asPercent == null) {
          throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
        }
        return asPercent;
      }
      return rate.setScale(4, java.math.RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private static String requireHttps(String raw) {
    String value = blankToNull(raw);
    if (value == null) {
      return null;
    }
    if (KolRules.length(value) > MAX_ADDRESS || !value.matches(KolRules.HTTPS_URL_PATTERN)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  private void recordAudit(String action, Object params) {
    var user = current.user();
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "KOL",
        action,
        "PUT",
        "/admin-api/kol/kols",
        sanitizer.sanitize(params));
  }

  private static String writeJson(Map<String, Object> map) {
    return JSON.writeValueAsString(map == null ? Map.of() : map);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return JSON.readValue(json, LinkedHashMap.class);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String country(String value) {
    return value == null || value.isBlank()
        ? null
        : value.strip().toUpperCase(java.util.Locale.ROOT);
  }

  public record PlatformInput(Long templateId, String address) {}

  public record PromotionConfigCommand(
      String defaultRate,
      List<PlatformInput> platforms,
      String inviteCode,
      String sheetUrl,
      Long jumpTemplateId,
      String remark) {}

  public record PromotionConfigDTO(
      String defaultRate,
      List<PlatformInput> platforms,
      String inviteCode,
      String sheetUrl,
      Long jumpTemplateId,
      String remark) {}

  public record PromotionRecordDTO(
      String changeType,
      Map<String, Object> before,
      Map<String, Object> after,
      String remark,
      String operatorName,
      Instant createTime,
      Integer versionNo,
      Instant effectiveTime) {}

  public record ReviewCommand(
      String name,
      String phone,
      String whatsapp,
      String primaryPlatform,
      String primaryHandle,
      String country,
      String remark) {}
}
