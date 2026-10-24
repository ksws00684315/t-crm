package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.commission.mapper.BizFileMapper;
import com.tcrm.admin.commission.model.BizFileDO;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Business files (crm_file, task 15a §4.2.8): KOL-uploaded QR images today, vouchers and prepay
 * attachments later. Rows are append-only except the one-time {@code bizId} backfill when the
 * owning business record is saved; a file belongs to exactly one business record, so a file with a
 * backfilled {@code bizId} can no longer be claimed.
 */
@Service
public class BizFileService {
  /** Download content: everything the caller needs to stream the attachment. */
  public record FileDTO(
      Long id, String fileName, String contentType, Long sizeBytes, byte[] content) {}

  /** A file plus the business record it belongs to, for authorization before a download. */
  public record AttachedFile(FileBizType bizType, Long bizId, FileDTO download) {}

  public record FileMeta(Long id, String name, String mimeType, Long size) {}

  private final BizFileMapper files;

  public BizFileService(BizFileMapper files) {
    this.files = files;
  }

  /**
   * Stores a KOL's image upload as an unattached {@code PAYOUT_QR} file. Type and size come from
   * the content itself ({@link FileRules}); the stored name is only for the download header.
   *
   * @return the new file id, referenced later when the account is saved
   */
  public Long uploadKolImage(long kolId, FileBizType bizType, String fileName, byte[] content) {
    String contentType = FileRules.requireImage(content);
    BizFileDO file = new BizFileDO();
    file.setBizType(bizType);
    file.setBizId(null);
    file.setFileName(fileName(fileName));
    file.setContentType(contentType);
    file.setSizeBytes((long) content.length);
    file.setContent(content);
    file.setCreateBy(kolId);
    file.setCreateByType("KOL");
    file.setCreateTime(Instant.now());
    files.insert(file);
    return file.getId();
  }

  /** Stores an attached prepay document; actual MIME is determined from its signature. */
  public Long uploadPrepayAdmin(
      long tenantId,
      long adminId,
      long prepayId,
      FileBizType bizType,
      String fileName,
      byte[] content) {
    if (bizType != FileBizType.PREPAY_ATTACHMENT && bizType != FileBizType.PREPAY_VOUCHER) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String contentType = PrepayFileRules.contentType(content);
    BizFileDO file = new BizFileDO();
    file.setTenantId(tenantId);
    file.setBizType(bizType);
    file.setBizId(prepayId);
    file.setFileName(fileName(fileName));
    file.setContentType(contentType);
    file.setSizeBytes((long) content.length);
    file.setContent(content);
    file.setCreateBy(adminId);
    file.setCreateByType("ADMIN");
    file.setCreateTime(Instant.now());
    files.insert(file);
    return file.getId();
  }

  /**
   * The file with this id when it was uploaded by the KOL; anything else is a 404, including files
   * uploaded by someone else (task 15a §4.3).
   */
  public FileDTO kolFile(long kolId, Long fileId) {
    BizFileDO file = files.selectById(fileId);
    if (file == null || !"KOL".equals(file.getCreateByType()) || file.getCreateBy() != kolId) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return toDto(file);
  }

  public FileDTO file(Long fileId) {
    BizFileDO file = files.selectById(fileId);
    if (file == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return toDto(file);
  }

  /** The file with its business link, or 404; callers authorize against the business record. */
  public AttachedFile attachedFile(Long fileId) {
    BizFileDO file = files.selectById(fileId);
    if (file == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return new AttachedFile(file.getBizType(), file.getBizId(), toDto(file));
  }

  public java.util.List<FileMeta> prepayAttachments(long prepayId) {
    return files
        .selectList(
            new LambdaQueryWrapper<BizFileDO>()
                .select(
                    BizFileDO::getId,
                    BizFileDO::getFileName,
                    BizFileDO::getContentType,
                    BizFileDO::getSizeBytes)
                .eq(BizFileDO::getBizType, FileBizType.PREPAY_ATTACHMENT)
                .eq(BizFileDO::getBizId, prepayId)
                .orderByAsc(BizFileDO::getId))
        .stream()
        .map(
            file ->
                new FileMeta(
                    file.getId(), file.getFileName(), file.getContentType(), file.getSizeBytes()))
        .toList();
  }

  /**
   * Claims the QR file for the account being saved: the file must be the KOL's own upload and still
   * unattached; the backfill of {@code bizId} happens in the caller's transaction together with the
   * account write (task 15a §4.2.8).
   */
  public void claimQrFile(long kolId, Long fileId, long accountId) {
    BizFileDO file = files.selectById(fileId);
    if (file == null
        || !"KOL".equals(file.getCreateByType())
        || file.getCreateBy() != kolId
        || file.getBizType() != FileBizType.PAYOUT_QR) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (file.getBizId() != null) {
      throw new BusinessException(ErrorCode.FILE_IN_USE);
    }
    // The IS NULL guard makes the claim atomic: when two accounts race for the same file only one
    // update matches a row; the loser sees 0 rows and rolls its transaction back.
    int updated =
        files.update(
            null,
            new LambdaUpdateWrapper<BizFileDO>()
                .eq(BizFileDO::getId, fileId)
                .isNull(BizFileDO::getBizId)
                .set(BizFileDO::getBizId, accountId));
    if (updated == 0) {
      throw new BusinessException(ErrorCode.FILE_IN_USE);
    }
  }

  private static FileDTO toDto(BizFileDO file) {
    return new FileDTO(
        file.getId(),
        file.getFileName(),
        file.getContentType(),
        file.getSizeBytes(),
        file.getContent());
  }

  /** Falls back to a stable name when the client sent none; keeps the column non-null. */
  private static String fileName(String original) {
    String name = original == null ? "" : original.strip();
    if (name.isEmpty()) {
      return "upload.png";
    }
    return name.length() > 255 ? name.substring(name.length() - 255) : name;
  }
}
