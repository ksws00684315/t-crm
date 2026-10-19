package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPlatformMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPlatformDO;
import com.tcrm.admin.kol.model.LoginStatus;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.LoginFailureCounter;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
  private final BdTransferService transfers;
  private final KolPlatformMapper platforms;
  private final LoginFailureCounter loginFailures;

  public KolService(
      KolMapper kols,
      KolBdHistoryMapper history,
      KolWriter writer,
      BdUserApi bdUsers,
      LinkTemplateService templates,
      CurrentUserContext current,
      TransactionTemplate tx,
      ApplicationEventPublisher events,
      KolInvites invites,
      BdTransferService transfers,
      KolPlatformMapper platforms,
      @org.springframework.beans.factory.annotation.Qualifier("kolLoginFailures")
          LoginFailureCounter loginFailures) {
    this.kols = kols;
    this.history = history;
    this.writer = writer;
    this.bdUsers = bdUsers;
    this.templates = templates;
    this.current = current;
    this.tx = tx;
    this.events = events;
    this.invites = invites;
    this.transfers = transfers;
    this.platforms = platforms;
    this.loginFailures = loginFailures;
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
            .eq(bd != null, KolDO::getBdUserId, bd);
    if (filter.reviewStatus() != null && !filter.reviewStatus().isBlank()) {
      query.eq(KolDO::getReviewStatus, filter.reviewStatus().strip());
    }
    applyRelationStatus(query, filter.relationStatus());
    applyLoginStatusFilter(query, filter.loginStatus());
    query.orderByDesc(KolDO::getCreateTime, KolDO::getId);
    var result = kols.selectPage(new Page<>(filter.page(), filter.size()), query);
    List<KolDO> rows = result.getRecords();
    Map<Long, String> names = bdNames(rows.stream().map(KolDO::getBdUserId));
    Map<Long, String> templates = templateNames(rows.stream().map(KolDO::getLinkTemplateId));
    Map<Long, List<KolDTO.PlatformDTO>> platformsMap =
        platformsByKol(rows.stream().map(KolDO::getId).toList(), templates);
    Map<Long, BdUserDTO> bdUsable = bdUsableById(rows.stream().map(KolDO::getBdUserId));
    Set<String> locked =
        loginFailures.findLockedUsernames(
            rows.stream().map(KolDO::getEmail).filter(Objects::nonNull).toList());
    return new PageResult<>(
        rows.stream()
            .map(kol -> toDto(kol, names, templates, platformsMap, bdUsable, locked))
            .toList(),
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
    Map<Long, String> templateMap = templateNames(Stream.of(kol.getLinkTemplateId()));
    Map<Long, List<KolDTO.PlatformDTO>> platformsMap =
        platformsByKol(List.of(kol.getId()), templateMap);
    Map<Long, BdUserDTO> bdUsable = bdUsableById(Stream.of(kol.getBdUserId()));
    Set<String> locked =
        loginFailures.findLockedUsernames(
            kol.getEmail() == null ? List.of() : List.of(kol.getEmail()));
    return new KolDetailDTO(
        toDto(kol, names, templateMap, platformsMap, bdUsable, locked),
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
    kol.setReviewStatus(ReviewStatus.PENDING.name());
    kol.setRelationStatus(RelationStatus.PENDING_PROMOTION.name());
    writer.insert(kol, null, user.id());
    // Re-read so timestamps match later reads (the database keeps milliseconds).
    KolDO saved = kols.selectById(kol.getId());
    // The invitation mail is sent after the row is committed (KolWriter commits its own
    // transaction); a transport failure is a WARN and the back office can resend (task 12a §4.4).
    // Excel imports bypass this method and never auto-invite.
    invites.issueAndSend(kol.getId());
    KolDO row = saved == null ? kol : saved;
    List<Long> selfId = row.getId() == null ? List.of() : List.of(row.getId());
    Map<Long, String> templateMap = templateNames(Stream.of(templateId));
    return toDto(
        row,
        bdNames(Stream.of(bd)),
        templateMap,
        platformsByKol(selfId, templateMap),
        bdUsableById(Stream.of(bd)),
        Set.of());
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
   * Updates profile fields only (task 28 §3.2 / §3.3). Email, commission rate, jump template,
   * invite code and sheet URL are not writable here — promotion config is the sole entry for the
   * latter four; email is read-only for every role. Uses a column whitelist so a concurrent {@link
   * #updateBdUser} cannot be overwritten with a stale {@code bd_user_id}.
   */
  public void update(Long id, EditKolCommand c) {
    AccountDTO user = current.user();
    requireVisible(id, user);
    Instant now = Instant.now();
    tx.executeWithoutResult(
        txStatus -> {
          int updated =
              kols.update(
                  null,
                  scoped(id, user)
                      .set(KolDO::getName, c.name().strip())
                      .set(KolDO::getPhone, blankToNull(c.phone()))
                      .set(KolDO::getCountry, country(c.country()))
                      .set(
                          KolDO::getSocialAccounts,
                          SocialAccountsJson.write(c.socialAccounts()),
                          "typeHandler=com.tcrm.admin.kol.model.JsonbStringTypeHandler")
                      .set(KolDO::getRemark, blankToNull(c.remark()))
                      .set(KolDO::getWhatsapp, blankToNull(c.whatsapp()))
                      .set(KolDO::getUpdateBy, user.id())
                      .set(KolDO::getUpdateTime, now));
          requireUpdated(updated, user);
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
  @Transactional(rollbackFor = Exception.class)
  public void updateBdUser(Long id, Long bdUserId) {
    AccountDTO user = current.user();
    if (user.ownScope()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    transfers.updateOne(id, bdUserId);
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

  /**
   * Filters by display relation status (task 29). {@code PENDING_UPDATE} = not INVALID and (no BD
   * or BD not usable); {@code INVALID} = stored INVALID; other values require stored status and
   * usable BD.
   */
  private void applyRelationStatus(LambdaQueryWrapper<KolDO> query, String relationStatus) {
    if (relationStatus == null || relationStatus.isBlank()) {
      return;
    }
    String status = relationStatus.strip();
    List<Long> usable =
        bdUsers.listUsable().stream().map(BdUserDTO::id).filter(Objects::nonNull).toList();
    if (RelationStatus.DISPLAY_PENDING_UPDATE.equals(status)) {
      query.ne(KolDO::getRelationStatus, RelationStatus.INVALID.name());
      if (usable.isEmpty()) {
        return;
      }
      query.and(w -> w.isNull(KolDO::getBdUserId).or().notIn(KolDO::getBdUserId, usable));
      return;
    }
    query.eq(KolDO::getRelationStatus, status);
    if (RelationStatus.INVALID.name().equals(status)) {
      return;
    }
    if (usable.isEmpty()) {
      query.apply("1 = 0");
    } else {
      query.in(KolDO::getBdUserId, usable);
    }
  }

  /** SQL-only loginStatus filter: FROZEN ↔ status=0; NORMAL_OR_ABNORMAL ↔ status=1. */
  private void applyLoginStatusFilter(LambdaQueryWrapper<KolDO> query, String loginStatus) {
    if (loginStatus == null || loginStatus.isBlank()) {
      return;
    }
    String status = loginStatus.strip();
    if (LoginStatus.FROZEN.name().equals(status)) {
      query.eq(KolDO::getStatus, 0);
    } else if (LoginStatus.FILTER_NORMAL_OR_ABNORMAL.equals(status)) {
      query.eq(KolDO::getStatus, 1);
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

  private KolDTO toDto(
      KolDO kol,
      Map<Long, String> bdNames,
      Map<Long, String> templates,
      Map<Long, List<KolDTO.PlatformDTO>> platformsMap,
      Map<Long, BdUserDTO> bdUsable,
      Set<String> lockedEmails) {
    Long bdId = kol.getBdUserId();
    BdUserDTO bd = bdId == null ? null : bdUsable.get(bdId);
    String review =
        kol.getReviewStatus() == null ? ReviewStatus.PENDING.name() : kol.getReviewStatus();
    String relation =
        kol.getRelationStatus() == null
            ? RelationStatus.PENDING_PROMOTION.name()
            : kol.getRelationStatus();
    String login = deriveLoginStatus(kol, lockedEmails);
    return new KolDTO(
        kol.getId(),
        kol.getKolCode(),
        kol.getName(),
        kol.getEmail(),
        kol.getPhone(),
        kol.getCountry(),
        SocialAccountsJson.read(kol.getSocialAccounts()),
        bdId,
        bdId == null ? null : displayBdName(bdNames, bdId, bd),
        kol.getDefaultRate(),
        kol.getStatus(),
        kol.getRemark(),
        kol.getWhatsapp(),
        kol.getLinkTemplateId(),
        kol.getLinkTemplateId() == null ? null : templates.get(kol.getLinkTemplateId()),
        kol.getInviteCode(),
        kol.getSheetUrl(),
        review,
        relation,
        KolPromotionSupport.displayRelationStatus(relation, bd),
        login,
        platformsMap.getOrDefault(kol.getId(), List.of()),
        kol.getConfigVersion(),
        kol.getCreateTime(),
        kol.getRegisterSource(),
        kol.getAccountStatus() == null ? 0 : kol.getAccountStatus(),
        kol.getCreateTime(),
        kol.getUpdateTime());
  }

  private static String displayBdName(Map<Long, String> bdNames, Long bdId, BdUserDTO bd) {
    if (bd != null) {
      String nickname = bd.nickname();
      if (nickname != null && !nickname.isBlank()) {
        return nickname;
      }
      return bd.username();
    }
    return bdNames.get(bdId);
  }

  private static String deriveLoginStatus(KolDO kol, Set<String> lockedEmails) {
    if (kol.getStatus() != null && kol.getStatus() == 0) {
      return LoginStatus.FROZEN.name();
    }
    int account = kol.getAccountStatus() == null ? 0 : kol.getAccountStatus();
    boolean locked = kol.getEmail() != null && lockedEmails.contains(kol.getEmail());
    if (account == 0 || locked) {
      return LoginStatus.ABNORMAL.name();
    }
    return LoginStatus.NORMAL.name();
  }

  private Map<Long, List<KolDTO.PlatformDTO>> platformsByKol(
      List<Long> kolIds, Map<Long, String> templateNames) {
    Map<Long, List<KolDTO.PlatformDTO>> out = new HashMap<>();
    if (kolIds.isEmpty()) {
      return out;
    }
    // Also load names for platform templates not already in map
    List<KolPlatformDO> rows =
        platforms.selectList(
            new LambdaQueryWrapper<KolPlatformDO>().in(KolPlatformDO::getKolId, kolIds));
    Set<Long> needed = new HashSet<>();
    for (KolPlatformDO row : rows) {
      if (!templateNames.containsKey(row.getLinkTemplateId())) {
        needed.add(row.getLinkTemplateId());
      }
    }
    Map<Long, String> extra = templates.namesByIds(needed);
    Map<Long, String> allNames = new HashMap<>(templateNames);
    allNames.putAll(extra);
    for (KolPlatformDO row : rows) {
      out.computeIfAbsent(row.getKolId(), id -> new ArrayList<>())
          .add(
              new KolDTO.PlatformDTO(
                  row.getLinkTemplateId(),
                  allNames.get(row.getLinkTemplateId()),
                  row.getAddress()));
    }
    for (List<KolDTO.PlatformDTO> list : out.values()) {
      list.sort(Comparator.comparing(KolDTO.PlatformDTO::templateId));
    }
    return out;
  }

  private Map<Long, BdUserDTO> bdUsableById(Stream<Long> ids) {
    Set<Long> wanted = new HashSet<>();
    ids.filter(Objects::nonNull).forEach(wanted::add);
    Map<Long, BdUserDTO> map = new HashMap<>();
    for (BdUserDTO bd : bdUsers.listByIds(wanted)) {
      map.put(bd.id(), bd);
    }
    return map;
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
