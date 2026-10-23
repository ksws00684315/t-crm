package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.Test;

class PrepayFileRulesTest {
  @Test
  void shouldAcceptPngJpegAndPdf_whenContentHasSupportedSignature() {
    assertThat(PrepayFileRules.contentType(png(8))).isEqualTo("image/png");
    assertThat(PrepayFileRules.contentType(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
        .isEqualTo("image/jpeg");
    assertThat(PrepayFileRules.contentType(new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d}))
        .isEqualTo("application/pdf");
  }

  @Test
  void shouldRejectUnsupportedOrEmptyContent_whenSignatureDoesNotMatch() {
    assertThatThrownBy(() -> PrepayFileRules.contentType(new byte[] {1, 2, 3}))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.PREPAY_FILE_INVALID);
    assertThatThrownBy(() -> PrepayFileRules.contentType(new byte[0]))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.PREPAY_FILE_INVALID);
  }

  @Test
  void shouldAllowExactlyTenMegabytesAndRejectLargerContent() {
    assertThat(PrepayFileRules.contentType(png(10 * 1024 * 1024))).isEqualTo("image/png");
    assertThatThrownBy(() -> PrepayFileRules.contentType(png(10 * 1024 * 1024 + 1)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.PREPAY_FILE_TOO_LARGE);
  }

  private static byte[] png(int size) {
    byte[] content = new byte[size];
    byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    System.arraycopy(signature, 0, content, 0, signature.length);
    return content;
  }
}
