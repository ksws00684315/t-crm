package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolCleanupApi;
import com.tcrm.admin.kol.api.KolCleanupConfigDTO;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.kol.mapper.KolCleanupConfigMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolCleanupConfigDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idle-KOL cleanup config and kol.api collaborators (task 25a / ADR-023 §5). Preview and scheduled
 * execution live in the order module so cross-module table access goes through api packages.
 */
@Service
public class KolCleanupService implements KolCleanupApi {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DEFAULT_IDLE_DAYS = 90;

  private final KolCleanupConfigMapper configs;
  private final KolMapper kols;
  private final KolPromotionRecordMapper records;
  private final CurrentUserContext current;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final ApplicationEventPublisher events;

  public KolCleanupService(
      KolCleanupConfigMapper configs,
      KolMapper kols,
      KolPromotionRecordMapper records,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events) {
    this.configs = configs;
    this.kols = kols;
    this.records = records;
    this.current = current;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.events = events;
  }

  public CleanupConfigDTO getConfig() {
    requireAllScope();
    return toDto(loadOrDefault());
  }

  @Override
  public KolCleanupConfigDTO getCleanupConfig() {
    return toApi(loadOrDefault());
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveConfig(boolean enabled, int idleDays) {
    requireAllScope();
    if (idleDays < 7 || idleDays > 365) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    AccountDTO user = current.user();
    KolCleanupConfigDO row =
        configs.selectOne(new LambdaQueryWrapper<KolCleanupConfigDO>().last("LIMIT 1"));
    Instant now = Instant.now();
    if (row == null) {
      row = new KolCleanupConfigDO();
      row.setEnabled(enabled);
      row.setIdleDays(idleDays);
      row.setCreateBy(user.id());
      row.setUpdateBy(user.id());
      configs.insert(row);
    } else {
      row.setEnabled(enabled);
      row.setIdleDays(idleDays);
      row.setUpdateBy(user.id());
      row.setUpdateTime(now);
      configs.updateById(row);
    }
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "KOL",
        "Save cleanup config",
        "PUT",
        "/admin-api/kol/cleanup-config",
        sanitizer.sanitize(Map.of("enabled", enabled, "idleDays", idleDays)));
  }

  @Override
  public List<PromotingKolDTO> listPromotingWithLastPromotingAt() {
    List<KolDO> promoting =
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .eq(KolDO::getPromotionStatus, PromotionStatus.PROMOTING.name())
                .orderByAsc(KolDO::getId));
    List<PromotingKolDTO> out = new ArrayList<>(promoting.size());
    for (KolDO kol : promoting) {
      Instant fallback = kol.getUpdateTime() == null ? kol.getCreateTime() : kol.getUpdateTime();
      out.add(
          new PromotingKolDTO(
              kol.getId(),
              kol.getKolCode(),
              kol.getName(),
              kol.getBdUserId(),
              lastPromotingSince(kol.getId()),
              fallback));
    }
    return out;
  }

  @Override
  public boolean softDeletePromotingKol(long kolId) {
    // Caller must already be in a transaction (order cleanup wraps close + delete).
    Long locked = kols.lockById(kolId);
    if (locked == null) {
      return false;
    }
    KolDO kol = kols.selectById(kolId);
    if (kol == null || !PromotionStatus.PROMOTING.name().equals(kol.getPromotionStatus())) {
      return false;
    }
    kols.deleteById(kolId);
    TransactionalEvents.publish(events, new KolStatusChangedEvent(kolId));
    return true;
  }

  /** Time of the latest STATUS change into PROMOTING; null when never recorded. */
  private Instant lastPromotingSince(Long kolId) {
    List<KolPromotionRecordDO> rows =
        records.selectList(
            new LambdaQueryWrapper<KolPromotionRecordDO>()
                .eq(KolPromotionRecordDO::getKolId, kolId)
                .eq(KolPromotionRecordDO::getChangeType, "STATUS")
                .orderByDesc(KolPromotionRecordDO::getCreateTime, KolPromotionRecordDO::getId)
                .last("LIMIT 50"));
    for (KolPromotionRecordDO row : rows) {
      Map<?, ?> after = parse(row.getAfterJson());
      if (PromotionStatus.PROMOTING.name().equals(String.valueOf(after.get("promotionStatus")))) {
        return row.getCreateTime();
      }
    }
    return null;
  }

  private KolCleanupConfigDO loadOrDefault() {
    KolCleanupConfigDO row =
        configs.selectOne(new LambdaQueryWrapper<KolCleanupConfigDO>().last("LIMIT 1"));
    if (row != null) {
      return row;
    }
    KolCleanupConfigDO defaults = new KolCleanupConfigDO();
    defaults.setEnabled(false);
    defaults.setIdleDays(DEFAULT_IDLE_DAYS);
    return defaults;
  }

  private void requireAllScope() {
    AccountDTO user = current.user();
    if (user.ownScope() || user.dataScope() == DataScope.OWN) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static CleanupConfigDTO toDto(KolCleanupConfigDO row) {
    return new CleanupConfigDTO(Boolean.TRUE.equals(row.getEnabled()), row.getIdleDays());
  }

  private static KolCleanupConfigDTO toApi(KolCleanupConfigDO row) {
    return new KolCleanupConfigDTO(Boolean.TRUE.equals(row.getEnabled()), row.getIdleDays());
  }

  private static Map<?, ?> parse(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return JSON.readValue(json, Map.class);
  }

  public record CleanupConfigDTO(boolean enabled, int idleDays) {}
}
