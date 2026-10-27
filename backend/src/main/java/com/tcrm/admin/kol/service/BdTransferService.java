package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.kol.api.KolsUnassignedEvent;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolBdTransferMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolBdTransferDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.DepartureHandoverEvent;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.api.StaffDisabledEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Ownership transitions only; order snapshots are maintained separately by task 13. */
@Service
public class BdTransferService {
  private static final Logger LOG = LoggerFactory.getLogger(BdTransferService.class);
  private final KolMapper kols;
  private final KolBdHistoryMapper history;
  private final KolBdTransferMapper transfers;
  private final BdUserApi bds;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  @Autowired
  public BdTransferService(
      KolMapper kols,
      KolBdHistoryMapper history,
      KolBdTransferMapper transfers,
      BdUserApi bds,
      CurrentUserContext current,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      ApplicationEventPublisher events) {
    this(kols, history, transfers, bds, current, tx, audit, sanitizer, Clock.systemUTC(), events);
  }

  BdTransferService(
      KolMapper kols,
      KolBdHistoryMapper history,
      KolBdTransferMapper transfers,
      BdUserApi bds,
      CurrentUserContext current,
      TransactionTemplate tx,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer,
      Clock clock,
      ApplicationEventPublisher events) {
    this.kols = kols;
    this.history = history;
    this.transfers = transfers;
    this.bds = bds;
    this.current = current;
    this.tx = tx;
    this.audit = audit;
    this.sanitizer = sanitizer;
    this.clock = clock;
    this.events = events;
  }

  @Transactional(rollbackFor = Exception.class)
  public BdTransferResultDTO create(BdTransferCommand command) {
    requireSuperAdmin();
    validate(command);
    return tx.execute(status -> createInTransaction(command));
  }

  /** Legacy PUT retains its existing service scope guard and uses the same immediate path. */
  void updateOne(Long kolId, Long target) {
    tx.executeWithoutResult(
        status ->
            createInTransaction(new BdTransferCommand(List.of(kolId), null, target, null, null)));
  }

  private BdTransferResultDTO createInTransaction(BdTransferCommand c) {
    // User row before KOL rows: serialize target disable with every assignment (design §4.13).
    requireTarget(c.toBdUserId());
    if (c.fromBdUserId() != null && bds.getById(c.fromBdUserId()) == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    List<Long> ids =
        c.kolIds() != null
            ? c.kolIds().stream().distinct().sorted().toList()
            : kols
                .selectList(
                    new LambdaQueryWrapper<KolDO>()
                        .select(KolDO::getId)
                        .eq(KolDO::getBdUserId, c.fromBdUserId())
                        .orderByAsc(KolDO::getId))
                .stream()
                .map(KolDO::getId)
                .toList();
    List<KolDO> locked = new ArrayList<>();
    for (Long id : ids) {
      KolDO kol = lockKol(id);
      if (c.fromBdUserId() == null || Objects.equals(kol.getBdUserId(), c.fromBdUserId())) {
        locked.add(kol);
      }
    }
    // Capture now after obtaining all locks so immediate periods cannot predate another writer.
    Instant now = now();
    boolean scheduled = c.effectiveAt() != null && c.effectiveAt().isAfter(now);
    Instant effective = scheduled ? c.effectiveAt().truncatedTo(ChronoUnit.MILLIS) : now;
    if (scheduled) {
      List<String> conflicts =
          locked.stream()
              .filter(
                  kol ->
                      transfers.selectCount(scheduled().eq(KolBdTransferDO::getKolId, kol.getId()))
                          > 0)
              .map(KolDO::getKolCode)
              .toList();
      if (!conflicts.isEmpty()) {
        throw new BdTransferExistsException(conflicts);
      }
    }
    int skipped = 0;
    List<KolDO> changedKols = new ArrayList<>();
    for (KolDO kol : locked) {
      if (!scheduled && Objects.equals(kol.getBdUserId(), c.toBdUserId())) {
        skipped++;
        continue;
      }
      KolBdTransferDO row = new KolBdTransferDO();
      row.setKolId(kol.getId());
      row.setFromBdUserId(kol.getBdUserId());
      row.setToBdUserId(c.toBdUserId());
      row.setEffectiveAt(effective);
      row.setStatus(scheduled ? BdTransferStatus.SCHEDULED : BdTransferStatus.DONE);
      row.setReason(c.reason());
      row.setDeleted(false);
      if (!scheduled) {
        changeOwnership(kol, c.toBdUserId(), now, current.user().id());
        row.setExecutedAt(now);
        cancelForKol(kol.getId(), "被立即转移取代", current.user().id());
      }
      transfers.insert(row);
      changedKols.add(kol);
    }
    if (!changedKols.isEmpty()) {
      record(
          "Create BD transfer",
          "POST",
          new TransferAuditDTO(
              changedKols.size(),
              c.fromBdUserId(),
              c.toBdUserId(),
              effective,
              c.reason(),
              changedKols.stream().map(KolDO::getBdUserId).distinct().toList(),
              transferSummary(
                  changedKols, effective, c.toBdUserId(), c.reason(), scheduled, skipped)));
    }
    int changed = changedKols.size();
    return new BdTransferResultDTO(scheduled ? 0 : changed, scheduled ? changed : 0, skipped);
  }

  public PageResult<BdTransferDTO> page(BdTransferFilterDTO f) {
    requireSuperAdmin();
    var query =
        new LambdaQueryWrapper<KolBdTransferDO>()
            .eq(f.status() != null, KolBdTransferDO::getStatus, f.status())
            .eq(f.fromBdUserId() != null, KolBdTransferDO::getFromBdUserId, f.fromBdUserId())
            .eq(f.toBdUserId() != null, KolBdTransferDO::getToBdUserId, f.toBdUserId())
            .orderByDesc(KolBdTransferDO::getEffectiveAt, KolBdTransferDO::getId);
    if (f.kolCode() != null && !f.kolCode().isBlank()) {
      KolDO kol =
          kols.selectOne(
              new LambdaQueryWrapper<KolDO>()
                  .select(KolDO::getId)
                  .eq(KolDO::getKolCode, f.kolCode().strip()));
      if (kol == null) {
        return new PageResult<>(List.of(), 0, f.page(), f.size());
      }
      query.eq(KolBdTransferDO::getKolId, kol.getId());
    }
    var result = transfers.selectPage(new Page<>(f.page(), f.size()), query);
    List<KolBdTransferDO> records = result.getRecords();
    if (records.isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), f.page(), f.size());
    }
    // Fixed per-page cost (task 16b 4.1): one batched KOL query and one batched BD query.
    Map<Long, KolDO> kolsById =
        kols
            .selectList(
                new LambdaQueryWrapper<KolDO>()
                    .select(KolDO::getId, KolDO::getKolCode, KolDO::getName)
                    .in(
                        KolDO::getId,
                        records.stream().map(KolBdTransferDO::getKolId).distinct().toList()))
            .stream()
            .collect(Collectors.toMap(KolDO::getId, Function.identity()));
    Map<Long, String> bdNames = bdNames(records);
    return new PageResult<>(
        records.stream().map(row -> toDto(row, kolsById, bdNames)).toList(),
        result.getTotal(),
        f.page(),
        f.size());
  }

  /** Nickname preferred, username otherwise (task 16b 4.1). */
  private Map<Long, String> bdNames(List<KolBdTransferDO> records) {
    Set<Long> ids = new HashSet<>();
    for (KolBdTransferDO row : records) {
      if (row.getFromBdUserId() != null) {
        ids.add(row.getFromBdUserId());
      }
      if (row.getToBdUserId() != null) {
        ids.add(row.getToBdUserId());
      }
    }
    Map<Long, String> names = new HashMap<>();
    for (BdUserDTO bd : bds.listByIds(ids)) {
      String nickname = bd.nickname();
      names.put(bd.id(), nickname == null || nickname.isBlank() ? bd.username() : nickname);
    }
    return names;
  }

  private static BdTransferDTO toDto(
      KolBdTransferDO row, Map<Long, KolDO> kolsById, Map<Long, String> bdNames) {
    KolDO kol = kolsById.get(row.getKolId());
    return new BdTransferDTO(
        row.getId(),
        row.getKolId(),
        kol == null ? null : kol.getKolCode(),
        kol == null ? null : kol.getName(),
        row.getFromBdUserId(),
        row.getFromBdUserId() == null ? null : bdNames.get(row.getFromBdUserId()),
        row.getToBdUserId(),
        row.getToBdUserId() == null ? null : bdNames.get(row.getToBdUserId()),
        row.getEffectiveAt(),
        row.getStatus(),
        row.getExecutedAt(),
        row.getReason(),
        row.getCancelReason(),
        row.getCreateTime());
  }

  @Transactional(rollbackFor = Exception.class)
  public void cancel(Long id) {
    requireSuperAdmin();
    tx.executeWithoutResult(
        status -> {
          KolBdTransferDO before = requireTransfer(id);
          lockKol(before.getKolId());
          int changed =
              transfers.update(
                  null,
                  scheduledUpdate(current.user().id())
                      .eq(KolBdTransferDO::getId, id)
                      .set(KolBdTransferDO::getStatus, BdTransferStatus.CANCELLED)
                      .set(KolBdTransferDO::getCancelReason, "手动取消"));
          if (changed != 1) {
            throw new BusinessException(ErrorCode.KOL_TRANSFER_NOT_SCHEDULED);
          }
          record(
              "Cancel BD transfer",
              "DELETE",
              new CancelAuditDTO(id, transferSummary(before, "取消预约", "手动取消")));
        });
  }

  /** One independent transaction per due record. A reread under the KOL lock makes retries safe. */
  @Transactional(rollbackFor = Exception.class)
  public void executeDue(Long id) {
    tx.executeWithoutResult(
        status -> {
          KolBdTransferDO candidate = transfers.selectById(id);
          if (candidate == null || candidate.getStatus() != BdTransferStatus.SCHEDULED) {
            return;
          }
          BdUserDTO target =
              candidate.getToBdUserId() == null ? null : bds.lockById(candidate.getToBdUserId());
          KolDO kol = lockKol(candidate.getKolId());
          KolBdTransferDO row = requireTransfer(id);
          if (row.getStatus() != BdTransferStatus.SCHEDULED
              || row.getEffectiveAt().isAfter(now())) {
            return;
          }
          if (!Objects.equals(kol.getBdUserId(), row.getFromBdUserId())) {
            cancelExecution(row, kol, "执行时归属已变化");
          } else if (row.getToBdUserId() != null && (target == null || !target.usable())) {
            cancelExecution(row, kol, "目标商务不可用");
            LOG.warn("BD transfer {} cancelled: target unavailable", id);
          } else {
            changeOwnership(kol, row.getToBdUserId(), row.getEffectiveAt(), null);
            transfers.update(
                null,
                scheduledUpdate(null)
                    .eq(KolBdTransferDO::getId, id)
                    .set(KolBdTransferDO::getStatus, BdTransferStatus.DONE)
                    .set(KolBdTransferDO::getExecutedAt, now()));
            record(
                "Execute BD transfer",
                "SYSTEM",
                new CancelAuditDTO(
                    row.getId(), transferSummary(row, kol, "预约转移已执行", row.getReason())),
                row.getTenantId(),
                row.getCreateBy());
          }
        });
  }

  /** At most 200 records per company per minute; the job holds the distributed lock. */
  public void executeDueBatch() {
    var rows =
        transfers.selectList(
            scheduled()
                .select(KolBdTransferDO::getId)
                .le(KolBdTransferDO::getEffectiveAt, now())
                .orderByAsc(KolBdTransferDO::getEffectiveAt, KolBdTransferDO::getId)
                .last("LIMIT 200"));
    for (KolBdTransferDO row : rows) {
      try {
        executeDue(row.getId());
      } catch (RuntimeException e) {
        LOG.error("BD transfer {} failed; retained for retry", row.getId(), e);
      }
    }
  }

  /**
   * Departure plan with an heir (task 41): the creators move before {@code StaffDisabledEvent} of
   * the same transaction would unassign them. Same ownership rules as an immediate transfer, but no
   * request credentials are read. A retry after a rolled-back attempt finds nothing left under the
   * departing BD, so nothing is written twice.
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onDepartureHandover(DepartureHandoverEvent event) {
    // Explicit company scope: the event may be published by another module in its transaction.
    TenantScope.runCompany(event.tenantId(), () -> handoverAll(event));
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onStaffDisabled(StaffDisabledEvent event) {
    // Explicit company scope: the event may be published by another module in its transaction.
    TenantScope.runCompany(event.tenantId(), () -> unassignDisabled(event));
  }

  private void handoverAll(DepartureHandoverEvent event) {
    if (event.toBdUserId() == null) {
      return;
    }
    List<KolDO> rows =
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .select(KolDO::getId)
                .eq(KolDO::getBdUserId, event.fromBdUserId())
                .orderByAsc(KolDO::getId));
    Instant effective = event.effectiveAt().truncatedTo(ChronoUnit.MILLIS);
    Instant executed = now();
    List<KolDO> moved = new ArrayList<>();
    for (KolDO row : rows) {
      KolDO kol = lockKol(row.getId());
      if (!Objects.equals(kol.getBdUserId(), event.fromBdUserId())) {
        continue;
      }
      changeOwnership(kol, event.toBdUserId(), effective, event.actorId());
      cancelForKol(kol.getId(), "被离职交接取代", event.actorId());
      KolBdTransferDO done = new KolBdTransferDO();
      done.setKolId(kol.getId());
      done.setFromBdUserId(event.fromBdUserId());
      done.setToBdUserId(event.toBdUserId());
      done.setEffectiveAt(effective);
      done.setStatus(BdTransferStatus.DONE);
      done.setExecutedAt(executed);
      done.setReason(event.reason());
      done.setDeleted(false);
      transfers.insert(done);
      moved.add(kol);
    }
    if (!moved.isEmpty()) {
      record(
          "Hand over departing BD KOLs",
          "SYSTEM",
          new HandoverAuditDTO(
              event.fromBdUserId(), event.toBdUserId(), moved.size(), effective, event.reason()),
          event.tenantId(),
          event.actorId());
    }
  }

  /**
   * Audit actor of a disable: the event names who to credit (task 41); an event without one uses
   * the signed-in administrator, and a scheduled execution without either credits the account
   * itself.
   */
  private Long actorOf(StaffDisabledEvent event) {
    if (event.actorId() != null) {
      return event.actorId();
    }
    try {
      return current.user().id();
    } catch (RuntimeException e) {
      return event.userId();
    }
  }

  private void unassignDisabled(StaffDisabledEvent event) {
    long userId = event.userId();
    Long actor = actorOf(event);
    List<KolDO> rows =
        kols.selectList(
            new LambdaQueryWrapper<KolDO>()
                .select(KolDO::getId)
                .eq(KolDO::getBdUserId, userId)
                .orderByAsc(KolDO::getId));
    int count = 0;
    for (KolDO row : rows) {
      KolDO kol = lockKol(row.getId());
      if (Objects.equals(kol.getBdUserId(), userId)) {
        changeOwnership(kol, null, now(), actor);
        count++;
      }
    }
    transfers.update(
        null,
        scheduledUpdate(actor)
            .eq(KolBdTransferDO::getFromBdUserId, userId)
            .set(KolBdTransferDO::getFromBdUserId, null));
    List<KolBdTransferDO> toDisabled =
        transfers.selectList(
            scheduled()
                .eq(KolBdTransferDO::getToBdUserId, userId)
                .orderByAsc(KolBdTransferDO::getId));
    for (KolBdTransferDO row : toDisabled) {
      int cancelled =
          transfers.update(
              null,
              scheduledUpdate(actor)
                  .eq(KolBdTransferDO::getId, row.getId())
                  .set(KolBdTransferDO::getStatus, BdTransferStatus.CANCELLED)
                  .set(KolBdTransferDO::getCancelReason, "目标商务已停用"));
      if (cancelled == 1) {
        record(
            "Cancel scheduled BD transfer",
            "SYSTEM",
            new CancelAuditDTO(row.getId(), transferSummary(row, "预约转移已取消", "目标商务已停用")),
            event.tenantId(),
            actor);
      }
    }
    record(
        "Unassign disabled staff KOLs",
        "SYSTEM",
        new DisabledAuditDTO(userId, count),
        event.tenantId(),
        actor);
    if (count > 0) {
      publishUnassigned(event, count);
    }
  }

  /**
   * Caller must hold the KOL row lock. See design §4.13: use the agreed time, clamped to current
   * history.
   */
  private void changeOwnership(KolDO kol, Long target, Instant effective, Long actor) {
    KolBdHistoryDO period =
        history.selectOne(
            new LambdaQueryWrapper<KolBdHistoryDO>()
                .eq(KolBdHistoryDO::getKolId, kol.getId())
                .isNull(KolBdHistoryDO::getEndTime));
    if (period != null && period.getStartTime().isAfter(effective)) {
      LOG.warn("BD transfer history time clamped for KOL {}", kol.getId());
      effective = period.getStartTime();
    }
    kols.update(
        null,
        new LambdaUpdateWrapper<KolDO>()
            .eq(KolDO::getId, kol.getId())
            .set(KolDO::getBdUserId, target)
            .set(KolDO::getUpdateBy, actor)
            .set(KolDO::getUpdateTime, now()));
    history.closeCurrent(kol.getId(), effective, actor);
    KolBdHistoryDO next = new KolBdHistoryDO();
    next.setKolId(kol.getId());
    next.setBdUserId(target);
    next.setStartTime(effective);
    next.setCreateBy(actor);
    next.setUpdateBy(actor);
    next.setDeleted(false);
    history.insert(next);
  }

  private void cancelExecution(KolBdTransferDO row, KolDO kol, String reason) {
    transfers.update(
        null,
        scheduledUpdate(null)
            .eq(KolBdTransferDO::getId, row.getId())
            .set(KolBdTransferDO::getStatus, BdTransferStatus.CANCELLED)
            .set(KolBdTransferDO::getCancelReason, reason));
    record(
        "Cancel scheduled BD transfer",
        "SYSTEM",
        new CancelAuditDTO(row.getId(), transferSummary(row, kol, "预约转移已取消", reason)),
        row.getTenantId(),
        row.getCreateBy());
    publishCancelled(row, kol, reason);
  }

  private String transferSummary(
      List<KolDO> rows,
      Instant effectiveAt,
      Long targetBdUserId,
      String reason,
      boolean scheduled,
      int skipped) {
    List<String> codes = rows.stream().map(KolDO::getKolCode).filter(Objects::nonNull).toList();
    List<Long> sourceIds = rows.stream().map(KolDO::getBdUserId).distinct().toList();
    String from = sourceIds.stream().map(this::bdName).distinct().collect(Collectors.joining("、"));
    String summary =
        String.format(
            "达人%s从%s转至%s，%s生效，原因：%s",
            codesSummary(codes),
            from.isBlank() ? "未分配" : from,
            bdName(targetBdUserId),
            scheduled ? "预约于 " + formatTransferTime(effectiveAt) : "立即",
            reason == null || reason.isBlank() ? "未填写" : reason);
    return skipped == 0 ? summary : summary + "；另有 " + skipped + " 位无变化，已跳过";
  }

  private String transferSummary(KolBdTransferDO row, KolDO kol, String outcome, String reason) {
    return String.format(
        "达人%s从%s转至%s，%s，生效时间：%s，原因：%s",
        kol.getKolCode() == null ? "未知编号" : kol.getKolCode(),
        bdName(row.getFromBdUserId()),
        bdName(row.getToBdUserId()),
        outcome,
        formatTransferTime(row.getEffectiveAt()),
        reason == null || reason.isBlank() ? "未填写" : reason);
  }

  private String transferSummary(KolBdTransferDO row, String outcome, String reason) {
    KolDO kol = kols.selectById(row.getKolId());
    String code = kol == null ? null : kol.getKolCode();
    return String.format(
        "达人%s从%s转至%s，%s，生效时间：%s，原因：%s",
        code == null ? "未知编号" : code,
        bdName(row.getFromBdUserId()),
        bdName(row.getToBdUserId()),
        outcome,
        formatTransferTime(row.getEffectiveAt()),
        reason == null || reason.isBlank() ? "未填写" : reason);
  }

  private String bdName(Long id) {
    if (id == null) {
      return "未分配";
    }
    BdUserDTO bd = bds.getById(id);
    if (bd == null) {
      return "未分配";
    }
    return bd.nickname() == null || bd.nickname().isBlank() ? bd.username() : bd.nickname();
  }

  private static String codesSummary(List<String> codes) {
    int shown = Math.min(20, codes.size());
    String summary = String.join("、", codes.subList(0, shown));
    return codes.size() > shown ? summary + "等 " + (codes.size() - shown) + " 位" : summary;
  }

  private static String formatTransferTime(Instant instant) {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC)
        .format(instant);
  }

  private void publishCancelled(KolBdTransferDO row, KolDO kol, String reason) {
    String code = cancelCode(reason);
    if (code == null) {
      return;
    }
    Long tenant = row.getTenantId() != null ? row.getTenantId() : TenantScope.companyId();
    if (tenant == null) {
      LOG.warn("BD transfer {} cancelled without a company; notification skipped", row.getId());
      return;
    }
    String kolCode = kol.getKolCode() == null ? "" : kol.getKolCode();
    TransactionalEvents.publish(
        events, new BdTransferCancelledEvent(tenant, kol.getId(), row.getId(), kolCode, code));
  }

  private void publishUnassigned(StaffDisabledEvent event, int count) {
    BdUserDTO bd = bds.getById(event.userId());
    String name = "";
    if (bd != null) {
      String nickname = bd.nickname();
      name = nickname != null && !nickname.isBlank() ? nickname : bd.username();
      if (name == null) {
        name = "";
      }
    }
    TransactionalEvents.publish(
        events, new KolsUnassignedEvent(event.tenantId(), event.userId(), name, count));
  }

  private static String cancelCode(String reason) {
    return switch (reason) {
      case "执行时归属已变化" -> "OWNERSHIP_CHANGED";
      case "目标商务不可用" -> "TARGET_UNAVAILABLE";
      default -> null;
    };
  }

  private void cancelForKol(Long kolId, String reason, Long actor) {
    List<KolBdTransferDO> rows =
        transfers.selectList(scheduled().eq(KolBdTransferDO::getKolId, kolId));
    for (KolBdTransferDO row : rows) {
      int cancelled =
          transfers.update(
              null,
              scheduledUpdate(actor)
                  .eq(KolBdTransferDO::getId, row.getId())
                  .set(KolBdTransferDO::getStatus, BdTransferStatus.CANCELLED)
                  .set(KolBdTransferDO::getCancelReason, reason));
      if (cancelled == 1) {
        record(
            "Cancel scheduled BD transfer",
            "SYSTEM",
            new CancelAuditDTO(row.getId(), transferSummary(row, "预约转移已取消", reason)),
            row.getTenantId(),
            actor);
      }
    }
  }

  private LambdaUpdateWrapper<KolBdTransferDO> scheduledUpdate(Long actor) {
    return new LambdaUpdateWrapper<KolBdTransferDO>()
        .eq(KolBdTransferDO::getStatus, BdTransferStatus.SCHEDULED)
        .set(KolBdTransferDO::getUpdateBy, actor)
        .set(KolBdTransferDO::getUpdateTime, now());
  }

  private static LambdaQueryWrapper<KolBdTransferDO> scheduled() {
    return new LambdaQueryWrapper<KolBdTransferDO>()
        .eq(KolBdTransferDO::getStatus, BdTransferStatus.SCHEDULED);
  }

  private KolDO lockKol(Long id) {
    if (kols.lockById(id) == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return kols.selectById(id);
  }

  private KolBdTransferDO requireTransfer(Long id) {
    KolBdTransferDO row = transfers.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  private void requireTarget(Long id) {
    if (id == null) {
      return;
    }
    BdUserDTO bd = bds.lockById(id);
    if (bd == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (!bd.usable()) {
      throw new BusinessException(ErrorCode.KOL_INVALID_BD_USER);
    }
  }

  private void requireSuperAdmin() {
    if (current.user().role() != Role.SUPER_ADMIN || current.user().ownScope()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private void validate(BdTransferCommand c) {
    boolean ids = c.kolIds() != null;
    if (ids == (c.fromBdUserId() != null)
        || (ids
            && (c.kolIds().isEmpty()
                || c.kolIds().size() > 500
                || c.kolIds().stream().anyMatch(id -> id == null || id <= 0)))
        || (c.fromBdUserId() != null && c.fromBdUserId() <= 0)
        || (c.toBdUserId() != null && c.toBdUserId() <= 0)
        || (c.reason() != null && c.reason().length() > 255)
        || (c.effectiveAt() != null
            && c.effectiveAt().isAfter(now().atZone(ZoneOffset.UTC).plusYears(1).toInstant()))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private void record(String action, String method, Object params) {
    var user = current.user();
    record(action, method, params, user.tenantId(), user.id());
  }

  private void record(String action, String method, Object params, long tenantId, Long actorId) {
    audit.recordTenantWriteOperation(
        tenantId,
        actorId == null ? 0L : actorId,
        "KOL",
        action,
        method,
        "/admin-api/kol/bd-transfers",
        sanitizer.sanitize(params));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private record TransferAuditDTO(
      int kolCount,
      Long fromBdUserId,
      Long toBdUserId,
      Instant effectiveAt,
      String reason,
      List<Long> sourceBdUserIds,
      String summary) {}

  private record DisabledAuditDTO(long disabledUserId, int kolCount) {}

  private record CancelAuditDTO(Long transferId, String summary) {}

  private record HandoverAuditDTO(
      Long fromBdUserId, Long toBdUserId, int kolCount, Instant effectiveAt, String reason) {}
}
