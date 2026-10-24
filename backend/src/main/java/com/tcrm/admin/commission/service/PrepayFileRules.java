package com.tcrm.admin.commission.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;

/** Signature-based validation for prepay files, which allow PNG, JPEG, and PDF up to 10 MB. */
final class PrepayFileRules {
  private static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};

  private PrepayFileRules() {}

  static String contentType(byte[] content) {
    if (content == null || content.length == 0) {
      throw new BusinessException(ErrorCode.PREPAY_FILE_INVALID);
    }
    if (content.length > MAX_BYTES) {
      throw new BusinessException(ErrorCode.PREPAY_FILE_TOO_LARGE);
    }
    if (startsWith(content, PNG)) {
      return "image/png";
    }
    if (startsWith(content, JPEG)) {
      return "image/jpeg";
    }
    if (startsWith(content, PDF)) {
      return "application/pdf";
    }
    throw new BusinessException(ErrorCode.PREPAY_FILE_INVALID);
  }

  private static boolean startsWith(byte[] content, byte[] signature) {
    if (content.length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if (content[index] != signature[index]) {
        return false;
      }
    }
    return true;
  }
}
