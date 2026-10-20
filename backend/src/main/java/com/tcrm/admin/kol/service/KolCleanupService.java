package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolCleanupApi;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idle-KOL cleanup config and kol.api collaborators (task 25a / 29 / ADR-025). Preview and
 * scheduled execution live in the order module so cross-module table access goes through api
 * packages.
 */
@Service
public class KolCleanupService implements KolCleanupApi {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final KolMapper kols;
  private final KolPromotionRecordMapper records;
  private final CurrentUserContext current;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final ApplicationEventPublisher events;

  public KolCleanupService(
      KolMapper kols,
      KolPromotionRecordMapper records,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events) {
    this.kols = kols;
    this.records = records;
    this.current = current;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.events = events;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void setAutoCleanupDays(long kolId, Integer days) {
    if (days != null && (days < 1 || days > 365)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    AccountDTO user = current.user();
    KolDO kol = kols.selectById(kolId);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope() && !user.id().equals(kol.getBdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    Integer beforeDays = kol.getAutoCleanupDays();
    Instant now = Instant.now();
    int updated =
        kols.update(
            null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>()
                .eq(KolDO::getId, kolId)
                .eq(user.ownScope(), KolDO::getBdUserId, user.id())
                .set(KolDO::getAutoCleanupDays, days)
                .set(KolDO::getUpdateBy, user.id())
                .set(KolDO::getUpdateTime, now));
    if (updated != 1) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    Map<String, Object> before =
        Map.of("autoCleanupDays", beforeDays == null ? "disabled" : beforeDays);
    Map<String, Object> after = Map.of("autoCleanupDays", days == null ? "disabled" : days);
    KolPromotionRecordDO record = new KolPromotionRecordDO();
    record.setKolId(kolId);
    record.setChangeType("AUTO_CLEANUP");
    record.setBeforeJson(JSON.writeValueAsString(before));
    record.setAfterJson(JSON.writeValueAsString(after));
    record.setRemark(null);
    record.setCreateBy(user.id());
    records.insert(record);
    String cleanupValue = days == null ? "关闭" : days + " 天";
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "KOL",
        "Set KOL auto cleanup",
        "PUT",
        "/admin-api/kol/kols/" + kolId + "/auto-cleanup",
        sanitizer.sanitize(
            Map.of(
                "kolId",
                kolId,
                "kolCode",
                kol.getKolCode(),
                "days",
                days == null ? "disabled" : days,
                "summary",
                "达人 " + kol.getKolCode() + " 自动清理设置为 " + cleanupValue)));
  }

  @Override
  public List<PromotingKolDTO> listPromotingWithLastPromotingAt() {
    List<KolDO> promoting =
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .eq(KolDO::getRelationStatus, RelationStatus.PROMOTING.name())
                .isNotNull(KolDO::getAutoCleanupDays)
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
              kol.getAutoCleanupDays(),
              lastPromotingSince(kol.getId()),
              fallback));
    }
    return out;
  }

  @Override
  public Optional<CleanupKolSnapshot> lockPromotingKol(long kolId) {
    Long locked = kols.lockById(kolId);
    if (locked == null) {
      return Optional.empty();
    }
    KolDO kol = kols.selectById(kolId);
    if (kol == null
        || !RelationStatus.PROMOTING.name().equals(kol.getRelationStatus())
        || kol.getAutoCleanupDays() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new CleanupKolSnapshot(
            kol.getId(),
            kol.getKolCode(),
            kol.getName(),
            kol.getBdUserId(),
            kol.getAutoCleanupDays()));
  }

  @Override
  public Optional<CleanupKolSnapshot> lockKol(long kolId) {
    Long locked = kols.lockById(kolId);
    if (locked == null) {
      return Optional.empty();
    }
    KolDO kol = kols.selectById(kolId);
    if (kol == null) {
      return Optional.empty();
    }
    return Optional.of(
        new CleanupKolSnapshot(
            kol.getId(),
            kol.getKolCode(),
            kol.getName(),
            kol.getBdUserId(),
            kol.getAutoCleanupDays()));
  }

  @Override
  public boolean softDeleteLockedKol(long kolId) {
    KolDO kol = kols.selectById(kolId);
    if (kol == null) {
      return false;
    }
    kols.deleteById(kolId);
    TransactionalEvents.publish(events, new KolStatusChangedEvent(kolId));
    return true;
  }

  /** Time of the latest promotion configuration saved into PROMOTING; null when never recorded. */
  private Instant lastPromotingSince(Long kolId) {
    List<KolPromotionRecordDO> rows =
        records.selectList(
            new LambdaQueryWrapper<KolPromotionRecordDO>()
                .eq(KolPromotionRecordDO::getKolId, kolId)
                .in(KolPromotionRecordDO::getChangeType, "STATUS", "CONFIG")
                .orderByDesc(KolPromotionRecordDO::getCreateTime, KolPromotionRecordDO::getId)
                .last("LIMIT 50"));
    for (KolPromotionRecordDO row : rows) {
      Map<?, ?> after = parse(row.getAfterJson());
      Object relation = after.get("relationStatus");
      if (relation == null) {
        relation = after.get("promotionStatus");
      }
      if (RelationStatus.PROMOTING.name().equals(String.valueOf(relation))) {
        return row.getCreateTime();
      }
    }
    return null;
  }

  private static Map<?, ?> parse(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return JSON.readValue(json, Map.class);
  }
}
