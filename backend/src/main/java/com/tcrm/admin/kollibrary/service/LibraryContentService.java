package com.tcrm.admin.kollibrary.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolContentMapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolMapper;
import com.tcrm.admin.kollibrary.model.LibraryContentForm;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import com.tcrm.admin.kollibrary.model.LibraryKolDO;
import com.tcrm.admin.kollibrary.model.LibraryOption;
import com.tcrm.admin.kollibrary.model.LibrarySampleStatus;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Content records of a library creator (requirement 1.2.3). Every record belongs to the creator in
 * the path: another creator's or another company's record id is 404. The calculated values are
 * derived on read through {@link LibraryMetrics} and never written.
 */
@Service
public class LibraryContentService {
  private final LibraryKolContentMapper contents;
  private final LibraryKolMapper kols;
  private final LibraryKolService creators;
  private final CurrentUserContext current;
  private final ObjectMapper json;

  public LibraryContentService(
      LibraryKolContentMapper contents,
      LibraryKolMapper kols,
      LibraryKolService creators,
      CurrentUserContext current,
      ObjectMapper json) {
    this.contents = contents;
    this.kols = kols;
    this.creators = creators;
    this.current = current;
    this.json = json;
  }

  /** All records of one creator, newest publication first. */
  public List<LibraryContentDTO> list(Long kolId) {
    creators.require(kolId);
    List<LibraryKolContentDO> rows =
        contents.selectList(
            new LambdaQueryWrapper<LibraryKolContentDO>()
                .eq(LibraryKolContentDO::getKolId, kolId)
                .orderByDesc(LibraryKolContentDO::getPublishDate, LibraryKolContentDO::getId));
    return rows.stream().map(LibraryContentService::toDto).toList();
  }

  /** One record with its calculated values; it must belong to {@code kolId}. */
  public LibraryContentDTO get(Long kolId, Long contentId) {
    return toDto(require(kolId, contentId));
  }

  @Transactional(rollbackFor = Exception.class)
  public LibraryContentDTO create(Long kolId, LibraryContentCommand command) {
    // Ownership check: another company's creator is "not found" before anything is written.
    creators.require(kolId);
    LibraryKolContentDO row = new LibraryKolContentDO();
    // The company column is filled by the tenant interceptor on insert (ADR-014), as elsewhere.
    row.setKolId(kolId);
    apply(row, command);
    contents.insert(row);
    touchCreator(kolId);
    return toDto(require(kolId, row.getId()));
  }

  @Transactional(rollbackFor = Exception.class)
  public LibraryContentDTO update(Long kolId, Long contentId, LibraryContentCommand command) {
    lock(kolId, contentId);
    LibraryKolContentDO row = new LibraryKolContentDO();
    apply(row, command);
    contents.update(
        null,
        new LambdaUpdateWrapper<LibraryKolContentDO>()
            .eq(LibraryKolContentDO::getId, contentId)
            .eq(LibraryKolContentDO::getKolId, kolId)
            .set(LibraryKolContentDO::getPublishUrl, row.getPublishUrl())
            .set(LibraryKolContentDO::getPublishTitle, row.getPublishTitle())
            .set(LibraryKolContentDO::getPublishDate, row.getPublishDate())
            .set(LibraryKolContentDO::getContentForm, row.getContentForm())
            .set(LibraryKolContentDO::getSampleStatus, row.getSampleStatus())
            .set(LibraryKolContentDO::getSampleCost, row.getSampleCost())
            .set(LibraryKolContentDO::getSlotFee, row.getSlotFee())
            .set(LibraryKolContentDO::getCommissionRate, row.getCommissionRate())
            .set(LibraryKolContentDO::getNextAction, row.getNextAction())
            .set(LibraryKolContentDO::getRemark, row.getRemark())
            .set(LibraryKolContentDO::getUpdateBy, current.id())
            .set(LibraryKolContentDO::getUpdateTime, Instant.now()));
    touchCreator(kolId);
    return toDto(require(kolId, contentId));
  }

  /** Soft-deletes one record. Super administrators only. */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long kolId, Long contentId) {
    require(kolId, contentId);
    contents.deleteById(contentId);
    touchCreator(kolId);
  }

  public List<WeeklyData> weeklyData(Long kolId, Long contentId) {
    require(kolId, contentId);
    return readWeeklyData(contentId);
  }

  @Transactional(rollbackFor = Exception.class)
  public List<WeeklyData> appendWeek(
      Long kolId, Long contentId, LocalDate weekStart, Long clicks, Long orders, BigDecimal gmv) {
    return saveWeek(kolId, contentId, weekStart, clicks, orders, gmv, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public List<WeeklyData> updateWeek(
      Long kolId, Long contentId, LocalDate weekStart, Long clicks, Long orders, BigDecimal gmv) {
    return saveWeek(kolId, contentId, weekStart, clicks, orders, gmv, true);
  }

  private List<WeeklyData> saveWeek(
      Long kolId,
      Long contentId,
      LocalDate weekStart,
      Long clicks,
      Long orders,
      BigDecimal gmv,
      boolean editing) {
    LibraryKolContentDO content = lock(kolId, contentId);
    var history = new ArrayList<>(readWeeklyData(contentId));
    WeeklyData previous =
        history.stream().filter(row -> row.weekStart().equals(weekStart)).findFirst().orElse(null);
    LocalDate currentWeek =
        LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    if (weekStart == null
        || weekStart.getDayOfWeek() != DayOfWeek.MONDAY
        || (!editing
            && (weekStart.isBefore(currentWeek.minusWeeks(9)) || weekStart.isAfter(currentWeek)))
        || (previous != null) != editing
        || clicks == null
        || orders == null
        || gmv == null
        || gmv.precision() - gmv.scale() > 12
        || gmv.scale() > 2) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    WeeklyData entry =
        new WeeklyData(
            weekStart, nonNegative(clicks), nonNegative(orders), amount(gmv), Instant.now());
    history.removeIf(row -> row.weekStart().equals(weekStart));
    history.add(entry);
    history.sort(Comparator.comparing(WeeklyData::weekStart));
    // Keep totals entered before weekly tracking. A correction replaces just that week's values.
    long totalClicks = Objects.requireNonNullElse(content.getClicks(), 0L);
    long totalOrders = Objects.requireNonNullElse(content.getOrders(), 0L);
    BigDecimal totalGmv = Objects.requireNonNullElse(content.getGmv(), BigDecimal.ZERO);
    try {
      if (previous != null) {
        totalClicks = Math.subtractExact(totalClicks, previous.clicks());
        totalOrders = Math.subtractExact(totalOrders, previous.orders());
        totalGmv = totalGmv.subtract(previous.gmv());
      }
      totalClicks = Math.addExact(totalClicks, entry.clicks());
      totalOrders = Math.addExact(totalOrders, entry.orders());
      totalGmv = totalGmv.add(entry.gmv());
    } catch (ArithmeticException e) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (totalGmv.precision() - totalGmv.scale() > 12) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    contents.saveWeeklyData(contentId, json.writeValueAsString(history));
    contents.update(
        null,
        new LambdaUpdateWrapper<LibraryKolContentDO>()
            .eq(LibraryKolContentDO::getId, contentId)
            .set(LibraryKolContentDO::getClicks, totalClicks)
            .set(LibraryKolContentDO::getOrders, totalOrders)
            .set(LibraryKolContentDO::getGmv, totalGmv)
            .set(LibraryKolContentDO::getUpdateBy, current.id())
            .set(LibraryKolContentDO::getUpdateTime, entry.updatedAt()));
    touchCreator(kolId);
    return List.copyOf(history);
  }

  private LibraryKolContentDO lock(Long kolId, Long contentId) {
    creators.require(kolId);
    LibraryKolContentDO content =
        contents.selectOne(
            new LambdaQueryWrapper<LibraryKolContentDO>()
                .eq(LibraryKolContentDO::getId, contentId)
                .eq(LibraryKolContentDO::getKolId, kolId)
                .last("FOR UPDATE"));
    if (content == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return content;
  }

  private List<WeeklyData> readWeeklyData(Long contentId) {
    String data = contents.weeklyData(contentId);
    if (data == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return java.util.Arrays.stream(json.readValue(data, WeeklyData[].class))
        .sorted(Comparator.comparing(WeeklyData::weekStart))
        .toList();
  }

  public record WeeklyData(
      LocalDate weekStart, Long clicks, Long orders, BigDecimal gmv, Instant updatedAt) {}

  /** The record when it belongs to this creator of this company; otherwise 404. */
  private LibraryKolContentDO require(Long kolId, Long contentId) {
    creators.require(kolId);
    LibraryKolContentDO row = contents.selectById(contentId);
    if (row == null || !kolId.equals(row.getKolId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  /** Bumps the creator's change time, which orders the list and runs before this record's. */
  private void touchCreator(Long kolId) {
    kols.update(
        null,
        new LambdaUpdateWrapper<LibraryKolDO>()
            .eq(LibraryKolDO::getId, kolId)
            .set(LibraryKolDO::getUpdateBy, current.id())
            .set(LibraryKolDO::getUpdateTime, Instant.now()));
  }

  /** Validates the manual fields into {@code row}. */
  private static void apply(LibraryKolContentDO row, LibraryContentCommand command) {
    row.setPublishUrl(httpUrl(command.publishUrl()));
    row.setPublishTitle(LibraryKolService.strip(command.publishTitle()));
    if (row.getPublishTitle() == null || row.getPublishTitle().length() > 200) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (command.publishDate() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    row.setPublishDate(command.publishDate());
    row.setContentForm(
        LibraryOption.of(LibraryContentForm.class, command.contentForm())
            .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)));
    row.setSampleStatus(
        LibraryKolService.blank(command.sampleStatus())
            ? null
            : LibraryOption.of(LibrarySampleStatus.class, command.sampleStatus())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)));
    row.setSampleCost(amount(command.sampleCost()));
    row.setSlotFee(amount(command.slotFee()));
    row.setCommissionRate(percent(command.commissionRate()));
    row.setNextAction(LibraryKolService.strip(command.nextAction()));
    row.setRemark(LibraryKolService.strip(command.remark()));
    if ((row.getNextAction() != null && row.getNextAction().length() > 1000)
        || (row.getRemark() != null && row.getRemark().length() > 1000)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  static LibraryContentDTO toDto(LibraryKolContentDO row) {
    LibraryMetrics.ContentMetrics metrics =
        LibraryMetrics.of(
            row.getGmv(), row.getSampleCost(), row.getSlotFee(), row.getCommissionRate());
    return new LibraryContentDTO(
        row.getId(),
        row.getPublishUrl(),
        row.getPublishTitle(),
        row.getWeeklyData() != null && !row.getWeeklyData().equals("[]"),
        row.getPublishDate(),
        row.getContentForm() == null ? null : row.getContentForm().name(),
        row.getClicks(),
        row.getOrders(),
        row.getGmv(),
        row.getSampleType() == null ? null : row.getSampleType().name(),
        row.getSampleCost(),
        row.getSlotFee(),
        row.getCommissionRate(),
        metrics.estimatedCommission(),
        metrics.totalCost(),
        metrics.actualRoi(),
        row.getNextAction(),
        row.getRemark(),
        row.getSampleStatus() == null ? null : row.getSampleStatus().name(),
        row.getUpdateTime());
  }

  private static String httpUrl(String value) {
    String text = LibraryKolService.strip(value);
    if (text == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (text.length() > 512 || !(lower.startsWith("https://") || lower.startsWith("http://"))) {
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

  /** CNY amount, two decimals, not negative; blank is null (counted as 0 by the metrics). */
  private static BigDecimal amount(BigDecimal value) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0 || value.stripTrailingZeros().scale() > LibraryMetrics.SCALE) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value.setScale(LibraryMetrics.SCALE);
  }

  /** Commission rate 0 to 100 with at most two decimals; blank is null (counted as 0). */
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
}
