package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPlatformMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPlatformDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.kol.model.PromotionChangeType;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Promotion status, config, and append-only records (task 25a / ADR-023). */
@Service
public class KolPromotionService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_LANDING = 512;
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

  public KolPromotionService(
      KolMapper kols,
      KolPlatformMapper platforms,
      KolPromotionRecordMapper records,
      LinkTemplateService templates,
      BdUserApi bdUsers,
      CurrentUserContext current,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer) {
    this.kols = kols;
    this.platforms = platforms;
    this.records = records;
    this.templates = templates;
    this.bdUsers = bdUsers;
    this.current = current;
    this.tx = tx;
    this.audit = audit;
    this.sanitizer = sanitizer;
  }

  public PromotionConfigDTO getConfig(Long id) {
    KolDO kol = requireVisible(id, current.user());
    List<Long> platformIds = platformIds(id);
    return toConfig(kol, platformIds);
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateConfig(Long id, PromotionConfigCommand command) {
    AccountDTO user = current.user();
    tx.executeWithoutResult(
        status -> {
          KolDO kol = requireVisible(id, user);
          List<Long> beforePlatforms = platformIds(id);
          Map<String, Object> before = KolPromotionSupport.snapshot(kol, beforePlatforms);
          applyConfig(kol, command, user.id());
          List<Long> afterPlatforms = platformIds(id);
          KolDO after = kols.selectById(id);
          Map<String, Object> afterSnap = KolPromotionSupport.snapshot(after, afterPlatforms);
          insertRecord(
              id,
              PromotionChangeType.CONFIG,
              before,
              afterSnap,
              blankToNull(command.remark()),
              user.id());
          recordAudit("Save promotion config", Map.of("kolId", id));
        });
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateStatus(Long id, PromotionStatus status) {
    AccountDTO user = current.user();
    tx.executeWithoutResult(
        txStatus -> {
          KolDO kol = requireVisible(id, user);
          List<Long> platformIds = platformIds(id);
          if (status == PromotionStatus.PROMOTING
              && !KolPromotionSupport.configComplete(kol, platformIds)) {
            throw new BusinessException(ErrorCode.KOL_PROMOTION_CONFIG_INCOMPLETE);
          }
          Map<String, Object> before = KolPromotionSupport.snapshot(kol, platformIds);
          Instant now = Instant.now();
          int updated =
              kols.update(
                  null,
                  scoped(id, user)
                      .set(KolDO::getPromotionStatus, status.name())
                      .set(KolDO::getUpdateBy, user.id())
                      .set(KolDO::getUpdateTime, now));
          requireUpdated(updated, user);
          KolDO after = kols.selectById(id);
          Map<String, Object> afterSnap = KolPromotionSupport.snapshot(after, platformIds);
          insertRecord(id, PromotionChangeType.STATUS, before, afterSnap, null, user.id());
          recordAudit("Change promotion status", Map.of("kolId", id, "status", status.name()));
        });
  }

  public PageResult<PromotionRecordDTO> pageRecords(Long id, int page, int size) {
    requireVisible(id, current.user());
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
                        r.getCreateTime()))
            .toList(),
        result.getTotal(),
        page,
        size);
  }

  private void applyConfig(KolDO kol, PromotionConfigCommand command, Long actorId) {
    BigDecimal rate = parseRate(command.defaultRate());
    String landing = requireHttps(command.landingUrl());
    String invite = blankToNull(command.inviteCode());
    if (invite != null && !invite.matches(KolRules.INVITE_CODE_PATTERN)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String sheet = blankToNull(command.sheetUrl());
    if (sheet != null && !sheet.matches(KolRules.SHEET_URL_PATTERN)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<Long> templateIds = normalizeTemplateIds(command.platformTemplateIds());
    Instant now = Instant.now();
    AccountDTO user = current.user();
    int updated =
        kols.update(
            null,
            scoped(kol.getId(), user)
                .set(KolDO::getDefaultRate, rate)
                .set(KolDO::getLandingUrl, landing)
                .set(KolDO::getInviteCode, invite)
                .set(KolDO::getSheetUrl, sheet)
                .set(KolDO::getUpdateBy, actorId)
                .set(KolDO::getUpdateTime, now));
    requireUpdated(updated, user);
    replacePlatforms(kol.getId(), templateIds, actorId);
  }

  private List<Long> normalizeTemplateIds(List<Long> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    Set<Long> seen = new HashSet<>();
    List<Long> out = new ArrayList<>();
    for (Long id : raw) {
      if (id == null || id <= 0 || !seen.add(id)) {
        continue;
      }
      templates.usableTemplate(id);
      out.add(id);
    }
    out.sort(Long::compareTo);
    return out;
  }

  private void replacePlatforms(Long kolId, List<Long> templateIds, Long actorId) {
    List<KolPlatformDO> existing =
        platforms.selectList(
            new LambdaQueryWrapper<KolPlatformDO>().eq(KolPlatformDO::getKolId, kolId));
    Set<Long> keep = new HashSet<>(templateIds);
    for (KolPlatformDO row : existing) {
      if (!keep.contains(row.getLinkTemplateId())) {
        platforms.deleteById(row.getId());
      }
    }
    Set<Long> have =
        existing.stream()
            .filter(r -> keep.contains(r.getLinkTemplateId()))
            .map(KolPlatformDO::getLinkTemplateId)
            .collect(Collectors.toSet());
    for (Long templateId : templateIds) {
      if (have.contains(templateId)) {
        continue;
      }
      KolPlatformDO row = new KolPlatformDO();
      row.setKolId(kolId);
      row.setLinkTemplateId(templateId);
      row.setCreateBy(actorId);
      row.setUpdateBy(actorId);
      row.setDeleted(false);
      platforms.insert(row);
    }
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

  private List<Long> platformIds(Long kolId) {
    return platforms
        .selectList(new LambdaQueryWrapper<KolPlatformDO>().eq(KolPlatformDO::getKolId, kolId))
        .stream()
        .map(KolPlatformDO::getLinkTemplateId)
        .sorted()
        .toList();
  }

  private static PromotionConfigDTO toConfig(KolDO kol, List<Long> platformIds) {
    return new PromotionConfigDTO(
        KolRules.rateText(kol.getDefaultRate()),
        platformIds,
        kol.getLandingUrl(),
        kol.getInviteCode(),
        kol.getSheetUrl(),
        null);
  }

  private KolDO requireVisible(Long id, AccountDTO user) {
    KolDO kol = kols.selectById(id);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope() && !user.id().equals(kol.getBdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return kol;
  }

  private static LambdaUpdateWrapper<KolDO> scoped(Long id, AccountDTO user) {
    return new LambdaUpdateWrapper<KolDO>()
        .eq(KolDO::getId, id)
        .eq(user.ownScope(), KolDO::getBdUserId, user.id());
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
    if (KolRules.length(value) > MAX_LANDING || !value.matches(KolRules.HTTPS_URL_PATTERN)) {
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

  public record PromotionConfigCommand(
      String defaultRate,
      List<Long> platformTemplateIds,
      String landingUrl,
      String inviteCode,
      String sheetUrl,
      String remark) {}

  public record PromotionConfigDTO(
      String defaultRate,
      List<Long> platformTemplateIds,
      String landingUrl,
      String inviteCode,
      String sheetUrl,
      String remark) {}

  public record PromotionRecordDTO(
      String changeType,
      Map<String, Object> before,
      Map<String, Object> after,
      String remark,
      String operatorName,
      Instant createTime) {}
}
