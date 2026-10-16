package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolLinkTargetChangedEvent;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * KOL management with BD data scope (task 04 §4.2, §4.3; design §3). Scope is enforced here, never
 * by the client: a staff account with OWN scope sees and changes only the KOLs assigned to them and
 * gets 403 for any other KOL. Write access is declared on the controller.
 */
@Service
public class KolService {
  private final KolMapper kols;
  private final KolBdHistoryMapper history;
  private final KolWriter writer;
  private final BdUserApi bdUsers;
  private final LinkTemplateService templates;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;
  private final KolInvites invites;

  public KolService(
      KolMapper kols,
      KolBdHistoryMapper history,
      KolWriter writer,
      BdUserApi bdUsers,
      LinkTemplateService templates,
      CurrentUserContext current,
      TransactionTemplate tx,
      ApplicationEventPublisher events,
      KolInvites invites) {
    this.kols = kols;
    this.history = history;
    this.writer = writer;
    this.bdUsers = bdUsers;
    this.templates = templates;
    this.current = current;
    this.tx = tx;
    this.events = events;
    this.invites = invites;
  }

  public PageResult<KolDTO> page(KolFilterDTO filter) {
    AccountDTO user = current.user();
    Long bd = user.ownScope() ? user.id() : filter.bdUserId();
    String keyword = filter.keyword() == null ? "" : filter.keyword().strip();
    String pattern = "%" + escapeLike(keyword) + "%";
    var query =
        new LambdaQueryWrapper<KolDO>()
            .apply(
                !keyword.isEmpty(),
                "(kol_code ILIKE {0} OR name ILIKE {1} OR email ILIKE {2})",
                pattern,
                pattern,
                pattern)
            .eq(
                filter.country() != null && !filter.country().isBlank(),
                KolDO::getCountry,
                filter.country() == null ? null : filter.country().strip().toUpperCase(Locale.ROOT))
            .eq(filter.status() != null, KolDO::getStatus, filter.status())
            .eq(bd != null, KolDO::getBdUserId, bd)
            .orderByDesc(KolDO::getCreateTime, KolDO::getId);
    var result = kols.selectPage(new Page<>(filter.page(), filter.size()), query);
    Map<Long, String> names = bdNames(result.getRecords().stream().map(KolDO::getBdUserId));
    Map<Long, String> templates =
        templateNames(result.getRecords().stream().map(KolDO::getLinkTemplateId));
    return new PageResult<>(
        result.getRecords().stream().map(kol -> toDto(kol, names, templates)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  public KolDetailDTO get(Long id) {
    KolDO kol = requireVisible(id, current.user());
    List<KolBdHistoryDO> periods =
        history.selectList(
            new LambdaQueryWrapper<KolBdHistoryDO>()
                .eq(KolBdHistoryDO::getKolId, id)
                .orderByDesc(KolBdHistoryDO::getStartTime, KolBdHistoryDO::getId));
    Map<Long, String> names =
        bdNames(
            Stream.concat(
                Stream.of(kol.getBdUserId()), periods.stream().map(KolBdHistoryDO::getBdUserId)));
    return new KolDetailDTO(
        toDto(kol, names, templateNames(Stream.of(kol.getLinkTemplateId()))),
        periods.stream()
            .map(
                p ->
                    new BdHistoryDTO(
                        p.getBdUserId(),
                        names.get(p.getBdUserId()),
                        p.getStartTime(),
                        p.getEndTime()))
            .toList());
  }

  public KolDTO create(CreateKolCommand c) {
    AccountDTO user = current.user();
    Long bd = user.ownScope() ? user.id() : usableBd(c.bdUserId());
    Long templateId = usableTemplate(c.linkTemplateId());
    String email = normalizeEmail(c.email());
    if (emailTaken(email, null)) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
    }
    KolDO kol = new KolDO();
    kol.setName(c.name().strip());
    kol.setEmail(email);
    kol.setCountry(country(c.country()));
    kol.setSocialAccounts(SocialAccountsJson.write(c.socialAccounts()));
    kol.setBdUserId(bd);
    kol.setDefaultRate(c.defaultRate());
    kol.setStatus(1);
    kol.setRemark(blankToNull(c.remark()));
    kol.setWhatsapp(blankToNull(c.whatsapp()));
    kol.setLinkTemplateId(templateId);
    kol.setInviteCode(blankToNull(c.inviteCode()));
    kol.setSheetUrl(blankToNull(c.sheetUrl()));
    kol.setRegisterSource(1);
    writer.insert(kol, null, user.id());
    // Re-read so timestamps match later reads (the database keeps milliseconds).
    KolDO saved = kols.selectById(kol.getId());
    // The invitation mail is sent after the row is committed (KolWriter commits its own
    // transaction); a transport failure is a WARN and the back office can resend (task 12a §4.4).
    // Excel imports bypass this method and never auto-invite.
    invites.issueAndSend(kol.getId());
    return toDto(
        saved == null ? kol : saved, bdNames(Stream.of(bd)), templateNames(Stream.of(templateId)));
  }

  /**
   * Resends the invitation: every unused invitation token is invalidated first, so only the newest
   * link stays usable. 409 for an already-activated KOL (task 12a §4.3).
   */
  public void resendInvite(Long id) {
    AccountDTO user = current.user();
    KolDO kol = requireVisible(id, user);
    if (Integer.valueOf(1).equals(kol.getAccountStatus())) {
      throw new BusinessException(ErrorCode.KOL_ALREADY_ACTIVATED);
    }
    invites.issueAndSend(id);
  }

  /**
   * Updates editable KOL fields only. Uses a column whitelist so a concurrent {@link #updateBdUser}
   * cannot be overwritten with a stale {@code bd_user_id} (never set here). A change of the link
   * target inputs (template or invite code) publishes {@link KolLinkTargetChangedEvent} in the same
   * transaction, registering a link index refresh (task 11 §4.4; ADR-013).
   */
  public void update(Long id, EditKolCommand c) {
    AccountDTO user = current.user();
    KolDO existing = requireVisible(id, user);
    Long templateId = usableTemplate(c.linkTemplateId());
    String email = normalizeEmail(c.email());
    if (emailTaken(email, id)) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
    }
    Instant now = Instant.now();
    tx.executeWithoutResult(
        txStatus -> {
          int updated;
          try {
            updated =
                kols.update(
                    null,
                    scoped(id, user)
                        .set(KolDO::getName, c.name().strip())
                        .set(KolDO::getEmail, email)
                        .set(KolDO::getCountry, country(c.country()))
                        .set(
                            KolDO::getSocialAccounts,
                            SocialAccountsJson.write(c.socialAccounts()),
                            "typeHandler=com.tcrm.admin.kol.model.JsonbStringTypeHandler")
                        .set(KolDO::getDefaultRate, c.defaultRate())
                        .set(KolDO::getRemark, blankToNull(c.remark()))
                        .set(KolDO::getWhatsapp, blankToNull(c.whatsapp()))
                        .set(KolDO::getLinkTemplateId, templateId)
                        .set(KolDO::getInviteCode, blankToNull(c.inviteCode()))
                        .set(KolDO::getSheetUrl, blankToNull(c.sheetUrl()))
                        .set(KolDO::getUpdateBy, user.id())
                        .set(KolDO::getUpdateTime, now));
          } catch (DuplicateKeyException e) {
            // Only uk_crm_kol_email can be violated by an edit; the code never changes here.
            throw new BusinessException(ErrorCode.KOL_EMAIL_EXISTS);
          }
          requireUpdated(updated, user);
          boolean targetChanged =
              !Objects.equals(existing.getLinkTemplateId(), templateId)
                  || !Objects.equals(existing.getInviteCode(), blankToNull(c.inviteCode()));
          if (targetChanged) {
            TransactionalEvents.publish(events, new KolLinkTargetChangedEvent(id));
          }
        });
  }

  /**
   * Enables or disables a KOL; never touches {@code bd_user_id}. Publishes {@link
   * KolStatusChangedEvent} in the same transaction; the link index is registered before commit and
   * applied afterwards (task 06b).
   */
  public void updateStatus(Long id, int status) {
    AccountDTO user = current.user();
    requireVisible(id, user);
    Instant now = Instant.now();
    tx.executeWithoutResult(
        txStatus -> {
          requireUpdated(
              kols.update(
                  null,
                  scoped(id, user)
                      .set(KolDO::getStatus, status)
                      .set(KolDO::getUpdateBy, user.id())
                      .set(KolDO::getUpdateTime, now)),
              user);
          TransactionalEvents.publish(events, new KolStatusChangedEvent(id));
        });
  }

  /**
   * Changes or clears the BD (task 04 §4.2): in one transaction the KOL row is locked, the current
   * ownership period closed and a new one opened, including changes to or from "unassigned".
   */
  public void updateBdUser(Long id, Long bdUserId) {
    AccountDTO user = current.user();
    if (user.ownScope()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    Long bd = usableBd(bdUserId);
    tx.executeWithoutResult(
        status -> {
          if (kols.lockById(id) == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          KolDO kol = kols.selectById(id);
          if (Objects.equals(kol.getBdUserId(), bd)) {
            return;
          }
          Instant now = Instant.now();
          kols.update(
              null,
              new LambdaUpdateWrapper<KolDO>()
                  .eq(KolDO::getId, id)
                  .set(KolDO::getBdUserId, bd)
                  .set(KolDO::getUpdateBy, user.id())
                  .set(KolDO::getUpdateTime, now));
          history.closeCurrent(id, now, user.id());
          KolBdHistoryDO period = new KolBdHistoryDO();
          period.setKolId(id);
          period.setBdUserId(bd);
          period.setStartTime(now);
          period.setDeleted(false);
          history.insert(period);
        });
  }

  /**
   * Update condition for one KOL. For a BD (OWN) the row must still be assigned to them when the
   * UPDATE runs, so a transfer committed after {@link #requireVisible} makes the write affect no
   * row instead of changing a KOL that now belongs to someone else.
   */
  private static LambdaUpdateWrapper<KolDO> scoped(Long id, AccountDTO user) {
    return new LambdaUpdateWrapper<KolDO>()
        .eq(KolDO::getId, id)
        .eq(user.ownScope(), KolDO::getBdUserId, user.id());
  }

  /** No row updated: the KOL left the BD's scope meanwhile (403), or no longer exists (404). */
  private static void requireUpdated(int updated, AccountDTO user) {
    if (updated == 0) {
      throw new BusinessException(
          user.ownScope() ? ErrorCode.AUTH_FORBIDDEN : ErrorCode.COMMON_NOT_FOUND);
    }
  }

  /** Validates a requested BD: null (unassigned) or a usable BD, else KOL_INVALID_BD_USER. */
  private Long usableBd(Long bdUserId) {
    if (bdUserId == null) {
      return null;
    }
    BdUserDTO bd = bdUsers.getById(bdUserId);
    if (bd == null || !bd.usable()) {
      throw new BusinessException(ErrorCode.KOL_INVALID_BD_USER);
    }
    return bd.id();
  }

  /** 404 when missing; 403 when a BD asks for a KOL assigned to someone else (task 04 §4.3). */
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

  private boolean emailTaken(String email, Long exceptId) {
    return kols.selectCount(
            new LambdaQueryWrapper<KolDO>()
                .apply("lower(email) = {0}", email)
                .ne(exceptId != null, KolDO::getId, exceptId))
        > 0;
  }

  private Map<Long, String> bdNames(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, String> names = new HashMap<>();
    for (BdUserDTO bd : bdUsers.listByIds(wanted)) {
      names.put(bd.id(), bd.username());
    }
    return names;
  }

  private static KolDTO toDto(KolDO kol, Map<Long, String> bdNames, Map<Long, String> templates) {
    return new KolDTO(
        kol.getId(),
        kol.getKolCode(),
        kol.getName(),
        kol.getEmail(),
        kol.getCountry(),
        SocialAccountsJson.read(kol.getSocialAccounts()),
        kol.getBdUserId(),
        kol.getBdUserId() == null ? null : bdNames.get(kol.getBdUserId()),
        kol.getDefaultRate(),
        kol.getStatus(),
        kol.getRemark(),
        kol.getWhatsapp(),
        kol.getLinkTemplateId(),
        kol.getLinkTemplateId() == null ? null : templates.get(kol.getLinkTemplateId()),
        kol.getInviteCode(),
        kol.getSheetUrl(),
        kol.getRegisterSource(),
        kol.getCreateTime(),
        kol.getUpdateTime());
  }

  /**
   * Upper-cases a country code; null or blank stays null (the column is optional since task 11,
   * self-registration may skip it until the BD completes the profile).
   */
  private static String country(String value) {
    return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
  }

  /** Validated template id: an enabled template of this company, or null (task 11 §4.3). */
  private Long usableTemplate(Long templateId) {
    return templateId == null ? null : templates.usableTemplate(templateId).id();
  }

  private Map<Long, String> templateNames(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    return templates.namesByIds(wanted);
  }

  static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  /** Escapes LIKE wildcards and the escape character itself (PostgreSQL default escape "\"). */
  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
