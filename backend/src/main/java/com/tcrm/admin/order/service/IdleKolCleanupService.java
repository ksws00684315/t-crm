package com.tcrm.admin.order.service;

import com.tcrm.admin.kol.api.KolCleanupApi;
import com.tcrm.admin.kol.api.KolCleanupApi.CleanupKolSnapshot;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.product.api.KolCleanupItemApi;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idle-KOL cleanup orchestration (task 25a / 29 / 33): order owns the scheduled and manual job so
 * pay-time and Weidian deletes go through api packages.
 */
@Service
public class IdleKolCleanupService {
  private final KolCleanupApi cleanup;
  private final KolItemApi items;
  private final KolCleanupItemApi cleanupItems;
  private final OrderMapper orders;
  private final CurrentUserContext current;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final TransactionTemplate tx;

  public IdleKolCleanupService(
      KolCleanupApi cleanup,
      KolItemApi items,
      KolCleanupItemApi cleanupItems,
      OrderMapper orders,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      TransactionTemplate tx) {
    this.cleanup = cleanup;
    this.items = items;
    this.cleanupItems = cleanupItems;
    this.orders = orders;
    this.current = current;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.tx = tx;
  }

  /** Runs for the current company; each KOL supplies its own idle threshold. */
  public int executeForCurrentTenant() {
    Instant now = Instant.now();
    List<IdleKol> idle = findIdle(now);
    int cleaned = 0;
    for (IdleKol candidate : idle) {
      if (cleanOne(candidate, now, "AUTO", null, null)) {
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
          sanitizer.sanitize(Map.of("cleaned", cleaned)));
    }
    return cleaned;
  }

  /** Days until the same idle threshold used by the scheduled cleanup, scoped to visible KOLs. */
  public Map<Long, Integer> remainingDays(List<Long> ids) {
    if (ids == null || ids.isEmpty() || ids.size() > 100) {
      return Map.of();
    }
    AccountDTO user = current.user();
    Set<Long> wanted = Set.copyOf(ids);
    List<PromotingKolDTO> promoting =
        cleanup.listPromotingWithLastPromotingAt().stream()
            .filter(kol -> wanted.contains(kol.id()))
            .filter(kol -> !user.ownScope() || user.id().equals(kol.bdUserId()))
            .toList();
    Map<Long, Instant> lastPay = lastPayByKol(promoting.stream().map(PromotingKolDTO::id).toList());
    Instant now = Instant.now();
    Map<Long, Integer> result = new HashMap<>();
    for (PromotingKolDTO kol : promoting) {
      Instant idleSince = lastPay.get(kol.id());
      if (idleSince == null) {
        idleSince = kol.lastPromotingAt();
      }
      if (idleSince == null) {
        idleSince = kol.fallbackIdleAt();
      }
      if (idleSince == null || kol.autoCleanupDays() == null) {
        continue;
      }
      long seconds =
          Duration.between(now, idleSince.plus(kol.autoCleanupDays(), ChronoUnit.DAYS))
              .getSeconds();
      result.put(kol.id(), (int) Math.max(0, (seconds + 86_399) / 86_400));
    }
    return result;
  }

  /** Manual cleanup for one KOL (task 29). Same effect as auto; reason required. */
  public void cleanupManual(long kolId, String reason) {
    AccountDTO user = current.user();
    String cleanedReason = requireReason(reason);
    Instant now = Instant.now();
    boolean ok =
        Boolean.TRUE.equals(
            tx.execute(
                status -> {
                  Optional<CleanupKolSnapshot> locked = cleanup.lockKol(kolId);
                  if (locked.isEmpty()) {
                    throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
                  }
                  CleanupKolSnapshot snap = locked.get();
                  if (user.ownScope() && !user.id().equals(snap.bdUserId())) {
                    throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
                  }
                  cleanupItems.createPendingItems(
                      snap.id(),
                      snap.kolCode(),
                      snap.bdUserId(),
                      "MANUAL",
                      cleanedReason,
                      now,
                      user.id());
                  items.closeCurrentMappingsForKol(snap.id(), now, user.id());
                  return cleanup.softDeleteLockedKol(snap.id());
                }));
    if (ok) {
      cleanupItems.enqueueDeletesForKol(kolId);
      audit.recordTenantWriteOperation(
          user.tenantId(),
          user.id(),
          "KOL",
          "Manual KOL cleanup",
          "POST",
          "/admin-api/kol/kols/" + kolId + "/cleanup",
          sanitizer.sanitize(Map.of("kolId", kolId)));
    }
  }

  /** Package-visible for unit tests: same per-KOL idle rule as scheduled execution. */
  List<IdleKol> findIdle(Instant now) {
    List<PromotingKolDTO> promoting = cleanup.listPromotingWithLastPromotingAt();
    Map<Long, Instant> lastPay = lastPayByKol(promoting.stream().map(PromotingKolDTO::id).toList());
    List<IdleKol> out = new ArrayList<>();
    for (PromotingKolDTO kol : promoting) {
      if (kol.autoCleanupDays() == null) {
        continue;
      }
      Instant cutoff = now.minus(kol.autoCleanupDays(), ChronoUnit.DAYS);
      Instant lastOrder = lastPay.get(kol.id());
      Instant idleSince = lastOrder != null ? lastOrder : kol.lastPromotingAt();
      if (idleSince == null) {
        idleSince = kol.fallbackIdleAt();
      }
      if (idleSince != null && idleSince.isBefore(cutoff)) {
        out.add(
            new IdleKol(kol.id(), kol.kolCode(), kol.name(), kol.bdUserId(), lastOrder, idleSince));
      }
    }
    return out;
  }

  private boolean cleanOne(
      IdleKol candidate, Instant now, String trigger, String reason, Long actorId) {
    Long kolId = candidate.id();
    Boolean deleted =
        tx.execute(
            status -> {
              Optional<CleanupKolSnapshot> locked = cleanup.lockPromotingKol(kolId);
              if (locked.isEmpty()) {
                return false;
              }
              CleanupKolSnapshot snap = locked.get();
              Integer days = snap.autoCleanupDays();
              if (days == null) {
                return false;
              }
              Instant cutoff = now.minus(days, ChronoUnit.DAYS);
              Instant lastOrder = lastPayByKol(List.of(kolId)).get(kolId);
              Instant idleSince = lastOrder != null ? lastOrder : candidate.idleSince();
              if (idleSince == null || !idleSince.isBefore(cutoff)) {
                return false;
              }
              cleanupItems.createPendingItems(
                  snap.id(), snap.kolCode(), snap.bdUserId(), trigger, reason, now, actorId);
              items.closeCurrentMappingsForKol(kolId, now, actorId);
              return cleanup.softDeleteLockedKol(kolId);
            });
    if (Boolean.TRUE.equals(deleted)) {
      cleanupItems.enqueueDeletesForKol(kolId);
      return true;
    }
    return false;
  }

  private Map<Long, Instant> lastPayByKol(List<Long> kolIds) {
    Map<Long, Instant> out = new HashMap<>();
    if (kolIds == null || kolIds.isEmpty()) {
      return out;
    }
    for (OrderMapper.KolLastPayRow row : orders.lastAttributedPayTimes(kolIds)) {
      if (row.getKolId() != null && row.getPayTime() != null) {
        out.put(row.getKolId(), row.getPayTime());
      }
    }
    return out;
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String value = reason.strip();
    if (value.length() > 200) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  record IdleKol(
      Long id,
      String kolCode,
      String name,
      Long bdUserId,
      Instant lastOrderAt,
      Instant idleSince) {}
}
