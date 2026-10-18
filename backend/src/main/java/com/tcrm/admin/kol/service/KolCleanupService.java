package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
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
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idle-KOL cleanup config, preview, and execution (task 25a / ADR-023 §5). Config and preview
 * require ALL data scope; the scheduled job runs in company scope with a distributed lock.
 */
@Service
public class KolCleanupService {
  private static final Logger LOG = LoggerFactory.getLogger(KolCleanupService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DEFAULT_IDLE_DAYS = 90;
  private static final int BATCH = 200;

  private final KolCleanupConfigMapper configs;
  private final KolMapper kols;
  private final KolPromotionRecordMapper records;
  private final BdUserApi bdUsers;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final ApplicationEventPublisher events;

  public KolCleanupService(
      KolCleanupConfigMapper configs,
      KolMapper kols,
      KolPromotionRecordMapper records,
      BdUserApi bdUsers,
      CurrentUserContext current,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events) {
    this.configs = configs;
    this.kols = kols;
    this.records = records;
    this.bdUsers = bdUsers;
    this.current = current;
    this.tx = tx;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.events = events;
  }

  public CleanupConfigDTO getConfig() {
    requireAllScope();
    KolCleanupConfigDO row = loadOrDefault();
    return new CleanupConfigDTO(Boolean.TRUE.equals(row.getEnabled()), row.getIdleDays());
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

  public PageResult<CleanupPreviewDTO> preview(int page, int size) {
    requireAllScope();
    KolCleanupConfigDO config = loadOrDefault();
    List<IdleKol> idle = findIdle(config.getIdleDays(), Instant.now());
    int from = Math.max(0, (page - 1) * size);
    int to = Math.min(idle.size(), from + size);
    List<IdleKol> slice = from >= idle.size() ? List.of() : idle.subList(from, to);
    Map<Long, String> bdNames = bdNames(slice.stream().map(IdleKol::bdUserId).toList());
    return new PageResult<>(
        slice.stream()
            .map(
                k ->
                    new CleanupPreviewDTO(
                        k.id(),
                        k.kolCode(),
                        k.name(),
                        k.bdUserId() == null ? null : bdNames.get(k.bdUserId()),
                        k.lastOrderAt(),
                        k.idleSince()))
            .toList(),
        idle.size(),
        page,
        size);
  }

  /** Runs for the current company scope; no-op when cleanup is disabled. */
  public int executeForCurrentTenant() {
    KolCleanupConfigDO config = loadOrDefault();
    if (!Boolean.TRUE.equals(config.getEnabled())) {
      return 0;
    }
    Instant now = Instant.now();
    List<IdleKol> idle = findIdle(config.getIdleDays(), now);
    int cleaned = 0;
    for (IdleKol candidate : idle) {
      if (cleanOne(candidate.id(), now)) {
        cleaned++;
      }
    }
    if (cleaned > 0) {
      Long tenant = TenantScope.companyId();
      audit.recordTenantWriteOperation(
          tenant == null ? 0L : tenant,
          0L,
          "KOL",
          "Idle KOL cleanup",
          "SYSTEM",
          "/internal/kol/cleanup",
          sanitizer.sanitize(Map.of("cleaned", cleaned, "idleDays", config.getIdleDays())));
    }
    return cleaned;
  }

  private boolean cleanOne(Long kolId, Instant now) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              Long locked = kols.lockById(kolId);
              if (locked == null) {
                return false;
              }
              KolDO kol = kols.selectById(kolId);
              if (kol == null
                  || !PromotionStatus.PROMOTING.name().equals(kol.getPromotionStatus())) {
                return false;
              }
              kols.closeItemMappings(kolId, now, null);
              kols.deleteById(kolId);
              TransactionalEvents.publish(events, new KolStatusChangedEvent(kolId));
              return true;
            }));
  }

  private List<IdleKol> findIdle(int idleDays, Instant now) {
    Instant cutoff = now.minus(idleDays, ChronoUnit.DAYS);
    List<KolDO> promoting =
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .eq(KolDO::getPromotionStatus, PromotionStatus.PROMOTING.name())
                .orderByAsc(KolDO::getId));
    List<IdleKol> out = new ArrayList<>();
    for (KolDO kol : promoting) {
      Instant lastOrder = kols.lastAttributedPayTime(kol.getId());
      Instant promotingSince = lastPromotingSince(kol.getId());
      Instant idleSince = lastOrder != null ? lastOrder : promotingSince;
      if (idleSince == null) {
        idleSince = kol.getUpdateTime() == null ? kol.getCreateTime() : kol.getUpdateTime();
      }
      if (idleSince != null && idleSince.isBefore(cutoff)) {
        out.add(
            new IdleKol(
                kol.getId(),
                kol.getKolCode(),
                kol.getName(),
                kol.getBdUserId(),
                lastOrder,
                idleSince));
      }
    }
    return out;
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

  private Map<Long, String> bdNames(List<Long> ids) {
    Map<Long, String> names = new HashMap<>();
    for (BdUserDTO bd : bdUsers.listByIds(ids.stream().filter(Objects::nonNull).toList())) {
      String nickname = bd.nickname();
      names.put(bd.id(), nickname == null || nickname.isBlank() ? bd.username() : nickname);
    }
    return names;
  }

  private static Map<?, ?> parse(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return JSON.readValue(json, Map.class);
  }

  private record IdleKol(
      Long id,
      String kolCode,
      String name,
      Long bdUserId,
      Instant lastOrderAt,
      Instant idleSince) {}

  public record CleanupConfigDTO(boolean enabled, int idleDays) {}

  public record CleanupPreviewDTO(
      Long id,
      String kolCode,
      String name,
      String bdUserName,
      Instant lastOrderAt,
      Instant idleSince) {}
}
