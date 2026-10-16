package com.tcrm.admin.commission.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;

/**
 * Image upload checks for business files (task 15a §4.2.8): the type comes from the file's magic
 * bytes, never from the file name or the client's Content-Type, and the size is capped at 2 MB.
 */
final class FileRules {
  static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  private FileRules() {}

  /**
   * Returns the real content type of the image, or throws FILE_TOO_LARGE / FILE_INVALID. The
   * extension and declared MIME type of the upload are ignored on purpose.
   */
  static String requireImage(byte[] content) {
    if (content == null || content.length == 0) {
      throw new BusinessException(ErrorCode.FILE_INVALID);
    }
    if (content.length > MAX_BYTES) {
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    }
    if (startsWith(content, PNG)) {
      return "image/png";
    }
    if (startsWith(content, JPEG)) {
      return "image/jpeg";
    }
    throw new BusinessException(ErrorCode.FILE_INVALID);
  }

  private static boolean startsWith(byte[] content, byte[] signature) {
    if (content.length < signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if (content[i] != signature[i]) {
        return false;
      }
    }
    return true;
  }
}
