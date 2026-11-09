package com.tcrm.admin.kollibrary.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kollibrary.mapper.LibraryKolContentMapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolMapper;
import com.tcrm.admin.kollibrary.model.LibraryAudienceAge;
import com.tcrm.admin.kollibrary.model.LibraryAudienceGender;
import com.tcrm.admin.kollibrary.model.LibraryCategory;
import com.tcrm.admin.kollibrary.model.LibraryCooperationStage;
import com.tcrm.admin.kollibrary.model.LibraryCountry;
import com.tcrm.admin.kollibrary.model.LibraryGrade;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import com.tcrm.admin.kollibrary.model.LibraryKolDO;
import com.tcrm.admin.kollibrary.model.LibraryLevel;
import com.tcrm.admin.kollibrary.model.LibraryOption;
import com.tcrm.admin.kollibrary.model.LibraryOutreachStatus;
import com.tcrm.admin.kollibrary.model.LibraryPlatform;
import com.tcrm.admin.kollibrary.model.LibrarySampleStatus;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator profiles of the library (requirements 1.2.1, 1.2.2, 1.2.6). The company comes from the
 * login only; the tenant line adds the predicate, so another company's creator is "not found".
 *
 * <p>Duplicate detection (requirement 1.2.6 ②): platform plus normalised creator ID is unique per
 * company. It is checked before writing and guaranteed by a partial unique index, so two
 * simultaneous submissions leave exactly one row. A hit answers {@link
 * ErrorCode#KOL_LIBRARY_DUPLICATE} with the existing creator's id and follow-up owner.
 *
 * <p>There is no data separation between accounts of a company (requirement P1-13): every account
 * holding {@code kol-library:view} sees every creator.
 */
@Service
public class LibraryKolService {
  private final LibraryKolMapper kols;
  private final LibraryKolContentMapper contents;
  private final CurrentUserContext current;

  public LibraryKolService(
      LibraryKolMapper kols, LibraryKolContentMapper contents, CurrentUserContext current) {
    this.kols = kols;
    this.contents = contents;
    this.current = current;
  }

  /** Paged list, most recently changed first. */
  public PageResult<LibraryKolDTO> page(LibraryKolFilter filter) {
    LambdaQueryWrapper<LibraryKolDO> query = new LambdaQueryWrapper<>();
    String keyword = strip(filter.keyword());
    if (keyword != null) {
      String pattern = "%" + escapeLike(keyword) + "%";
      String username = LibraryUsernames.normalize(keyword);
      query.and(
          w ->
              w.apply("kol_username LIKE {0}", pattern)
                  .or()
                  .apply("lower(display_name) LIKE lower({0})", pattern)
                  .or(!username.isEmpty(), x -> x.eq(LibraryKolDO::getKolUsername, username)));
    }
    LibraryPlatform platform =
        LibraryOption.of(LibraryPlatform.class, filter.platform()).orElse(null);
    if (platform != null) {
      query.eq(LibraryKolDO::getPlatform, platform);
    }
    LibraryCountry country =
        LibraryOption.of(LibraryCountry.class, filter.countryCode()).orElse(null);
    if (country != null) {
      query.eq(LibraryKolDO::getCountryCode, country);
    }
    LibraryCategory category =
        LibraryOption.of(LibraryCategory.class, filter.category()).orElse(null);
    if (category != null) {
      // "Contains this category": a JSONB containment test on the category array.
      query.apply("categories @> jsonb_build_array({0})", category.name());
    }
    LibraryOutreachStatus outreach =
        LibraryOption.of(LibraryOutreachStatus.class, filter.outreachStatus()).orElse(null);
    if (outreach != null) {
      query.eq(LibraryKolDO::getOutreachStatus, outreach);
    }
    String owner = strip(filter.followUpOwner());
    if (owner != null) {
      query.apply("follow_up_owner ILIKE {0}", "%" + escapeLike(owner) + "%");
    }
    query.orderByDesc(LibraryKolDO::getUpdateTime, LibraryKolDO::getId);
    var result = kols.selectPage(new Page<>(filter.page(), filter.size()), query);
    return new PageResult<>(
        result.getRecords().stream().map(LibraryKolService::toDto).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  /** Full profile with the creator's totals; another company's creator is 404. */
  public LibraryKolDTO.Detail get(Long id) {
    LibraryKolDO kol = require(id);
    return toDetail(kol, totalsOf(List.of(kol.getId())).get(kol.getId()));
  }

  /**
   * Creates a profile. Answers {@link ErrorCode#KOL_LIBRARY_DUPLICATE} when the creator is already
   * in the library, whatever account entered it.
   */
  @Transactional(rollbackFor = Exception.class)
  public LibraryKolDTO.Detail create(LibraryKolCommand command) {
    LibraryKolDO row = new LibraryKolDO();
    row.setTenantId(current.user().tenantId());
    applyProfile(row, command, true);
    // The pre-check answers the common duplicate with the existing creator. The insert then runs
    // on its own: after a unique violation PostgreSQL aborts the whole transaction, so the existing
    // row has to be read BEFORE the insert, not after a failed one.
    requireUnique(row, null);
    LibraryKolDO existing = existing(row).orElse(null);
    try {
      kols.insert(row);
    } catch (DuplicateKeyException e) {
      // uk_crm_library_kol_username: a simultaneous request entered the same creator first.
      throw existing == null
          ? new BusinessException(ErrorCode.KOL_LIBRARY_DUPLICATE)
          : duplicate(existing);
    }
    // A new creator has no content yet: zero totals and a dash for the ROI.
    return toDetail(require(row.getId()), LibraryMetrics.totals(List.of()));
  }

  /**
   * Replaces the profile of a creator; the follow-up fields are written here too, because the edit
   * form contains all six groups. Editing the creator ID to another existing one is a duplicate.
   */
  @Transactional(rollbackFor = Exception.class)
  public LibraryKolDTO.Detail update(Long id, LibraryKolCommand command) {
    LibraryKolDO row = require(id);
    applyProfile(row, command, false);
    Optional<LibraryKolDO> previous = existing(row);
    requireUnique(row, id);
    LambdaUpdateWrapper<LibraryKolDO> update =
        new LambdaUpdateWrapper<LibraryKolDO>().eq(LibraryKolDO::getId, id);
    setColumns(update, row);
    update
        .set(LibraryKolDO::getUpdateBy, current.id())
        .set(LibraryKolDO::getUpdateTime, Instant.now());
    try {
      kols.update(null, update);
    } catch (DuplicateKeyException e) {
      // A simultaneous edit took this ID first; the transaction is aborted, so the answer uses the
      // result of the check above rather than a new read.
      throw duplicate(previous.orElse(row));
    }
    LibraryKolDO saved = require(id);
    return toDetail(saved, totalsOf(List.of(id)).get(id));
  }

  /** Follow-up dialog: only the five follow-up fields change. */
  @Transactional(rollbackFor = Exception.class)
  public LibraryKolDTO.Detail updateFollowUp(Long id, LibraryFollowUpCommand command) {
    require(id);
    LibraryOutreachStatus outreach =
        required(
            LibraryOutreachStatus.class,
            command.outreachStatus(),
            ErrorCode.COMMON_VALIDATION_FAILED);
    LibrarySampleStatus sample =
        required(
            LibrarySampleStatus.class, command.sampleStatus(), ErrorCode.COMMON_VALIDATION_FAILED);
    kols.update(
        null,
        new LambdaUpdateWrapper<LibraryKolDO>()
            .eq(LibraryKolDO::getId, id)
            .set(LibraryKolDO::getOutreachStatus, outreach)
            .set(LibraryKolDO::getLastContactDate, command.lastContactDate())
            .set(LibraryKolDO::getSampleStatus, sample)
            .set(LibraryKolDO::getTrackingNo, strip(command.trackingNo()))
            .set(LibraryKolDO::getSampleDate, command.sampleDate())
            .set(LibraryKolDO::getUpdateBy, current.id())
            .set(LibraryKolDO::getUpdateTime, Instant.now()));
    return get(id);
  }

  /** Soft-deletes the creator and all of its content records. Super administrators only. */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    require(id);
    contents.delete(
        new LambdaQueryWrapper<LibraryKolContentDO>().eq(LibraryKolContentDO::getKolId, id));
    kols.deleteById(id);
  }

  /**
   * Looks up a creator of this company, or 404 (another company's id is absent for the tenant
   * line).
   */
  LibraryKolDO require(Long id) {
    LibraryKolDO row = kols.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  /** Totals of these creators, in one grouped content query; creators without rows get zeros. */
  java.util.Map<Long, LibraryMetrics.Totals> totalsOf(List<Long> kolIds) {
    java.util.Map<Long, LibraryMetrics.Totals> result = new java.util.HashMap<>();
    if (kolIds.isEmpty()) {
      return result;
    }
    List<LibraryKolContentDO> rows =
        contents.selectList(
            new LambdaQueryWrapper<LibraryKolContentDO>()
                .select(
                    LibraryKolContentDO::getKolId,
                    LibraryKolContentDO::getClicks,
                    LibraryKolContentDO::getOrders,
                    LibraryKolContentDO::getGmv,
                    LibraryKolContentDO::getSampleCost,
                    LibraryKolContentDO::getSlotFee,
                    LibraryKolContentDO::getCommissionRate)
                .in(LibraryKolContentDO::getKolId, kolIds));
    java.util.Map<Long, List<LibraryMetrics.ContentRecord>> grouped = new java.util.HashMap<>();
    for (LibraryKolContentDO row : rows) {
      grouped
          .computeIfAbsent(row.getKolId(), k -> new ArrayList<>())
          .add(
              new LibraryMetrics.ContentRecord(
                  row.getClicks(),
                  row.getOrders(),
                  row.getGmv(),
                  row.getSampleCost(),
                  row.getSlotFee(),
                  row.getCommissionRate()));
    }
    for (Long id : kolIds) {
      result.put(id, LibraryMetrics.totals(grouped.getOrDefault(id, List.of())));
    }
    return result;
  }

  /** Validates the command into {@code row}; the creator ID is normalised before validation. */
  private void applyProfile(LibraryKolDO row, LibraryKolCommand command, boolean creating) {
    String username = LibraryUsernames.normalize(command.kolUsername());
    if (!LibraryUsernames.valid(username)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    row.setKolUsername(username);
    row.setPlatform(
        required(LibraryPlatform.class, command.platform(), ErrorCode.COMMON_VALIDATION_FAILED));
    row.setCountryCode(
        required(LibraryCountry.class, command.countryCode(), ErrorCode.COMMON_VALIDATION_FAILED));
    row.setDisplayName(requiredText(command.displayName()));
    List<String> categories = options(LibraryCategory.class, command.categories(), true);
    row.setCategories(LibraryJson.write(categories));
    row.setFollowers(nonNegative(command.followers()));
    row.setProfileUrl(httpUrl(command.profileUrl()));
    row.setAvgViews(nonNegative(command.avgViews()));
    row.setEngagementRate(percent(command.engagementRate()));
    row.setAudienceCountry(text(command.audienceCountry(), 64));
    row.setAudienceAgeRange(optional(LibraryAudienceAge.class, command.audienceAgeRange()));
    row.setAudienceGender(optional(LibraryAudienceGender.class, command.audienceGender()));
    row.setContact(text(command.contact(), 128));
    row.setContentQuality(optional(LibraryLevel.class, command.contentQuality()));
    row.setSalesPotential(optional(LibraryLevel.class, command.salesPotential()));
    row.setCooperationAttitude(optional(LibraryLevel.class, command.cooperationAttitude()));
    row.setGrade(optional(LibraryGrade.class, command.grade()));
    row.setCooperationStage(optional(LibraryCooperationStage.class, command.cooperationStage()));
    row.setTags(tags(command.tags()));
    row.setRemark(text(command.remark(), 1000));
    row.setOutreachStatus(
        creating && blank(command.outreachStatus())
            ? LibraryOutreachStatus.NOT_CONTACTED
            : required(
                LibraryOutreachStatus.class,
                command.outreachStatus(),
                ErrorCode.COMMON_VALIDATION_FAILED));
    row.setFollowUpOwner(requiredText(command.followUpOwner()));
    row.setLastContactDate(command.lastContactDate());
    row.setSampleStatus(
        creating && blank(command.sampleStatus())
            ? LibrarySampleStatus.NOT_SENT
            : required(
                LibrarySampleStatus.class,
                command.sampleStatus(),
                ErrorCode.COMMON_VALIDATION_FAILED));
    row.setTrackingNo(text(command.trackingNo(), 64));
    row.setSampleDate(command.sampleDate());
  }

  /** Writes every profile column of {@code row} onto the update, nulls included. */
  private static void setColumns(LambdaUpdateWrapper<LibraryKolDO> update, LibraryKolDO row) {
    update
        .set(LibraryKolDO::getPlatform, row.getPlatform())
        .set(LibraryKolDO::getKolUsername, row.getKolUsername())
        .set(LibraryKolDO::getDisplayName, row.getDisplayName())
        .set(LibraryKolDO::getFollowers, row.getFollowers())
        .set(LibraryKolDO::getCountryCode, row.getCountryCode())
        .set(LibraryKolDO::getProfileUrl, row.getProfileUrl())
        .set(
            LibraryKolDO::getCategories,
            row.getCategories(),
            "typeHandler=com.tcrm.admin.kollibrary.model.JsonbStringTypeHandler")
        .set(LibraryKolDO::getAvgViews, row.getAvgViews())
        .set(LibraryKolDO::getEngagementRate, row.getEngagementRate())
        .set(LibraryKolDO::getAudienceCountry, row.getAudienceCountry())
        .set(LibraryKolDO::getAudienceAgeRange, row.getAudienceAgeRange())
        .set(LibraryKolDO::getAudienceGender, row.getAudienceGender())
        .set(LibraryKolDO::getContact, row.getContact())
        .set(LibraryKolDO::getContentQuality, row.getContentQuality())
        .set(LibraryKolDO::getSalesPotential, row.getSalesPotential())
        .set(LibraryKolDO::getCooperationAttitude, row.getCooperationAttitude())
        .set(LibraryKolDO::getGrade, row.getGrade())
        .set(LibraryKolDO::getCooperationStage, row.getCooperationStage())
        .set(
            LibraryKolDO::getTags,
            row.getTags(),
            "typeHandler=com.tcrm.admin.kollibrary.model.JsonbStringTypeHandler")
        .set(LibraryKolDO::getRemark, row.getRemark())
        .set(LibraryKolDO::getOutreachStatus, row.getOutreachStatus())
        .set(LibraryKolDO::getFollowUpOwner, row.getFollowUpOwner())
        .set(LibraryKolDO::getLastContactDate, row.getLastContactDate())
        .set(LibraryKolDO::getSampleStatus, row.getSampleStatus())
        .set(LibraryKolDO::getTrackingNo, row.getTrackingNo())
        .set(LibraryKolDO::getSampleDate, row.getSampleDate());
  }

  /** Fails with KOL_LIBRARY_DUPLICATE and the existing creator when platform + ID are taken. */
  private void requireUnique(LibraryKolDO row, Long self) {
    existing(row)
        .filter(found -> !Objects.equals(found.getId(), self))
        .ifPresent(
            found -> {
              throw duplicate(found);
            });
  }

  /** The creator already holding this platform and ID in the company, if any. */
  private Optional<LibraryKolDO> existing(LibraryKolDO row) {
    return Optional.ofNullable(
        kols.selectOne(
            new LambdaQueryWrapper<LibraryKolDO>()
                .select(
                    LibraryKolDO::getId,
                    LibraryKolDO::getFollowUpOwner,
                    LibraryKolDO::getDisplayName)
                .eq(LibraryKolDO::getPlatform, row.getPlatform())
                .eq(LibraryKolDO::getKolUsername, row.getKolUsername())));
  }

  private static BusinessException duplicate(LibraryKolDO existing) {
    return new BusinessException(
        ErrorCode.KOL_LIBRARY_DUPLICATE,
        java.util.Map.of(
            "id", existing.getId(),
            "followUpOwner", existing.getFollowUpOwner() == null ? "" : existing.getFollowUpOwner(),
            "displayName", existing.getDisplayName() == null ? "" : existing.getDisplayName()));
  }

  static LibraryKolDTO toDto(LibraryKolDO row) {
    return new LibraryKolDTO(
        row.getId(),
        row.getPlatform() == null ? null : row.getPlatform().name(),
        row.getKolUsername(),
        row.getDisplayName(),
        row.getFollowers(),
        row.getCountryCode() == null ? null : row.getCountryCode().name(),
        LibraryJson.read(row.getCategories()),
        row.getGrade() == null ? null : row.getGrade().name(),
        row.getCooperationStage() == null ? null : row.getCooperationStage().name(),
        row.getOutreachStatus() == null ? null : row.getOutreachStatus().name(),
        row.getFollowUpOwner(),
        row.getLastContactDate(),
        row.getSampleStatus() == null ? null : row.getSampleStatus().name(),
        row.getUpdateTime());
  }

  static LibraryKolDTO.Detail toDetail(LibraryKolDO row, LibraryMetrics.Totals totals) {
    LibraryKolDTO.Totals dtoTotals =
        new LibraryKolDTO.Totals(
            totals.contentCount(),
            totals.clicks(),
            totals.orders(),
            totals.gmv(),
            totals.totalCost(),
            totals.roi());
    return new LibraryKolDTO.Detail(
        row.getId(),
        name(row.getPlatform()),
        row.getKolUsername(),
        row.getDisplayName(),
        row.getFollowers(),
        name(row.getCountryCode()),
        row.getProfileUrl(),
        LibraryJson.read(row.getCategories()),
        row.getAvgViews(),
        row.getEngagementRate(),
        row.getAudienceCountry(),
        name(row.getAudienceAgeRange()),
        name(row.getAudienceGender()),
        row.getContact(),
        name(row.getContentQuality()),
        name(row.getSalesPotential()),
        name(row.getCooperationAttitude()),
        name(row.getGrade()),
        name(row.getCooperationStage()),
        LibraryJson.read(row.getTags()),
        row.getRemark(),
        name(row.getOutreachStatus()),
        row.getFollowUpOwner(),
        row.getLastContactDate(),
        name(row.getSampleStatus()),
        row.getTrackingNo(),
        row.getSampleDate(),
        row.getCreateTime(),
        row.getUpdateTime(),
        dtoTotals);
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static <E extends Enum<E> & LibraryOption> E required(
      Class<E> type, String value, ErrorCode error) {
    return LibraryOption.of(type, value).orElseThrow(() -> new BusinessException(error));
  }

  /** An optional single choice; an unknown value is a validation failure. */
  private static <E extends Enum<E> & LibraryOption> E optional(Class<E> type, String value) {
    return blank(value) ? null : required(type, value, ErrorCode.COMMON_VALIDATION_FAILED);
  }

  /** Multi-choice values in registry order; at least one when required. */
  private static <E extends Enum<E> & LibraryOption> List<String> options(
      Class<E> type, List<String> values, boolean required) {
    EnumSet<E> selected = EnumSet.noneOf(type);
    if (values != null) {
      for (String value : values) {
        if (blank(value)) {
          continue;
        }
        selected.add(required(type, value, ErrorCode.COMMON_VALIDATION_FAILED));
      }
    }
    if (required && selected.isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return selected.stream().map(Enum::name).toList();
  }

  /** Free tags: trimmed, no blanks, no duplicates, at most 10 of at most 20 characters. */
  private static String tags(List<String> values) {
    List<String> tags =
        values == null
            ? List.of()
            : values.stream().map(String::strip).filter(v -> !v.isEmpty()).distinct().toList();
    if (tags.size() > 10 || tags.stream().anyMatch(tag -> tag.length() > 20)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return LibraryJson.write(tags);
  }

  private static String requiredText(String value) {
    String text = strip(value);
    if (text == null || text.length() > 64) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return text;
  }

  /** Optional text with a length limit; blank becomes null. */
  private static String text(String value, int max) {
    String text = strip(value);
    if (text == null) {
      return null;
    }
    if (text.length() > max) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return text;
  }

  /** Optional http(s) link; anything else is a validation failure. */
  private static String httpUrl(String value) {
    String text = text(value, 512);
    if (text == null) {
      return null;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (!(lower.startsWith("https://") || lower.startsWith("http://"))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return text;
  }

  private static Long nonNegative(Long value) {
    if (value != null && value < 0) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  /** Percentage 0 to 100 with at most two decimals. */
  private static BigDecimal percent(BigDecimal value) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0
        || value.compareTo(BigDecimal.valueOf(100)) > 0
        || value.stripTrailingZeros().scale() > LibraryMetrics.SCALE) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value.setScale(LibraryMetrics.SCALE);
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  static String strip(String value) {
    if (value == null) {
      return null;
    }
    String text = value.strip();
    return text.isEmpty() ? null : text;
  }

  /** Escapes LIKE wildcards and the escape character itself (PostgreSQL default escape "\"). */
  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
