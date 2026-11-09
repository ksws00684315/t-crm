package com.tcrm.admin.kollibrary.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolContentMapper;
import com.tcrm.admin.kollibrary.mapper.LibraryKolMapper;
import com.tcrm.admin.kollibrary.model.LibraryContentForm;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import com.tcrm.admin.kollibrary.model.LibraryKolDO;
import com.tcrm.admin.kollibrary.model.LibraryOption;
import com.tcrm.admin.kollibrary.model.LibrarySampleType;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  public LibraryContentService(
      LibraryKolContentMapper contents,
      LibraryKolMapper kols,
      LibraryKolService creators,
      CurrentUserContext current) {
    this.contents = contents;
    this.kols = kols;
    this.creators = creators;
    this.current = current;
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
    LibraryKolDO kol = creators.require(kolId);
    LibraryKolContentDO row = new LibraryKolContentDO();
    row.setTenantId(kol.getTenantId());
    row.setKolId(kolId);
    apply(row, command);
    contents.insert(row);
    touchCreator(kolId);
    return toDto(require(kolId, row.getId()));
  }

  @Transactional(rollbackFor = Exception.class)
  public LibraryContentDTO update(Long kolId, Long contentId, LibraryContentCommand command) {
    require(kolId, contentId);
    LibraryKolContentDO row = new LibraryKolContentDO();
    apply(row, command);
    contents.update(
        null,
        new LambdaUpdateWrapper<LibraryKolContentDO>()
            .eq(LibraryKolContentDO::getId, contentId)
            .eq(LibraryKolContentDO::getKolId, kolId)
            .set(LibraryKolContentDO::getPublishUrl, row.getPublishUrl())
            .set(LibraryKolContentDO::getPublishDate, row.getPublishDate())
            .set(LibraryKolContentDO::getContentForm, row.getContentForm())
            .set(LibraryKolContentDO::getClicks, row.getClicks())
            .set(LibraryKolContentDO::getOrders, row.getOrders())
            .set(LibraryKolContentDO::getGmv, row.getGmv())
            .set(LibraryKolContentDO::getSampleType, row.getSampleType())
            .set(LibraryKolContentDO::getSampleCost, row.getSampleCost())
            .set(LibraryKolContentDO::getSlotFee, row.getSlotFee())
            .set(LibraryKolContentDO::getCommissionRate, row.getCommissionRate())
            .set(LibraryKolContentDO::getNextAction, row.getNextAction())
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
    if (command.publishDate() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    row.setPublishDate(command.publishDate());
    row.setContentForm(
        LibraryOption.of(LibraryContentForm.class, command.contentForm())
            .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)));
    row.setClicks(nonNegative(command.clicks()));
    row.setOrders(nonNegative(command.orders()));
    row.setGmv(amount(command.gmv()));
    row.setSampleType(
        LibraryKolService.blank(command.sampleType())
            ? null
            : LibraryOption.of(LibrarySampleType.class, command.sampleType())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)));
    row.setSampleCost(amount(command.sampleCost()));
    row.setSlotFee(amount(command.slotFee()));
    row.setCommissionRate(percent(command.commissionRate()));
    row.setNextAction(LibraryKolService.strip(command.nextAction()));
    if (row.getNextAction() != null && row.getNextAction().length() > 1000) {
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
