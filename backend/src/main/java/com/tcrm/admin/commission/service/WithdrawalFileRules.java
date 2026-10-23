package com.tcrm.admin.commission.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;

/** Signature-based validation for a withdrawal payment voucher (10 MB maximum). */
final class WithdrawalFileRules {
  private static final int MAX_BYTES = 10 * 1024 * 1024;

  private WithdrawalFileRules() {}

  static String contentType(byte[] content) {
    if (content == null || content.length == 0) {
      throw new BusinessException(ErrorCode.PREPAY_FILE_INVALID);
    }
    if (content.length > MAX_BYTES) {
      throw new BusinessException(ErrorCode.PREPAY_FILE_TOO_LARGE);
    }
    if (startsWith(content, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
      return "image/png";
    }
    if (startsWith(content, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
      return "image/jpeg";
    }
    if (startsWith(content, new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d})) {
      return "application/pdf";
    }
    if (content.length >= 12
        && content[0] == 'R'
        && content[1] == 'I'
        && content[2] == 'F'
        && content[3] == 'F'
        && content[8] == 'W'
        && content[9] == 'E'
        && content[10] == 'B'
        && content[11] == 'P') {
      return "image/webp";
    }
    throw new BusinessException(ErrorCode.PREPAY_FILE_INVALID);
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
