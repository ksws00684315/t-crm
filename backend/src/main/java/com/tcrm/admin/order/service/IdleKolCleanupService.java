package com.tcrm.admin.order.service;

import com.tcrm.admin.kol.api.KolCleanupApi;
import com.tcrm.admin.kol.api.KolCleanupConfigDTO;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idle-KOL cleanup orchestration (task 25a architecture review): order owns the job and preview so
 * pay-time reads stay in this module; kol/product mutations go through their api packages.
 */
@Service
public class IdleKolCleanupService {
  private final KolCleanupApi cleanup;
  private final KolItemApi items;
  private final OrderMapper orders;
  private final BdUserApi bdUsers;
  private final CurrentUserContext current;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final TransactionTemplate tx;

  public IdleKolCleanupService(
      KolCleanupApi cleanup,
      KolItemApi items,
      OrderMapper orders,
      BdUserApi bdUsers,
      CurrentUserContext current,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      TransactionTemplate tx) {
    this.cleanup = cleanup;
    this.items = items;
    this.orders = orders;
    this.bdUsers = bdUsers;
    this.current = current;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.tx = tx;
  }

  public PageResult<CleanupPreviewDTO> preview(int page, int size) {
    requireAllScope();
    KolCleanupConfigDTO config = cleanup.getCleanupConfig();
    List<IdleKol> idle = findIdle(config.idleDays(), Instant.now());
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
    KolCleanupConfigDTO config = cleanup.getCleanupConfig();
    if (!config.enabled()) {
      return 0;
    }
    Instant now = Instant.now();
    List<IdleKol> idle = findIdle(config.idleDays(), now);
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
          sanitizer.sanitize(Map.of("cleaned", cleaned, "idleDays", config.idleDays())));
    }
    return cleaned;
  }

  /** Package-visible for unit tests: same idle判定 as preview and execute. */
  List<IdleKol> findIdle(int idleDays, Instant now) {
    Instant cutoff = now.minus(idleDays, ChronoUnit.DAYS);
    List<PromotingKolDTO> promoting = cleanup.listPromotingWithLastPromotingAt();
    Map<Long, Instant> lastPay = lastPayByKol(promoting.stream().map(PromotingKolDTO::id).toList());
    List<IdleKol> out = new ArrayList<>();
    for (PromotingKolDTO kol : promoting) {
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

  private boolean cleanOne(Long kolId, Instant now) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              // Lock + verify PROMOTING before closing mappings so a non-idle race closes nothing.
              if (!cleanup.softDeletePromotingKol(kolId)) {
                return false;
              }
              items.closeCurrentMappingsForKol(kolId, now, null);
              return true;
            }));
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

  record IdleKol(
      Long id,
      String kolCode,
      String name,
      Long bdUserId,
      Instant lastOrderAt,
      Instant idleSince) {}

  public record CleanupPreviewDTO(
      Long id,
      String kolCode,
      String name,
      String bdUserName,
      Instant lastOrderAt,
      Instant idleSince) {}
}
