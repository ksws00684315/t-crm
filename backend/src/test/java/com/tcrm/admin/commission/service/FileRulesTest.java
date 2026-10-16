package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.ErrorCode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FileRulesTest {
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
  };
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
  private static final byte[] TEXT = "pretending to be a png".getBytes();

  @Test
  void shouldDetectTypeByMagicBytes() {
    assertThat(FileRules.requireImage(PNG)).isEqualTo("image/png");
    assertThat(FileRules.requireImage(JPEG)).isEqualTo("image/jpeg");
  }

  @Test
  void shouldRejectOtherContentRegardlessOfName() {
    assertThatThrownBy(() -> FileRules.requireImage(TEXT))
        .hasMessage(ErrorCode.FILE_INVALID.message());
    assertThatThrownBy(() -> FileRules.requireImage(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00}))
        .hasMessage(ErrorCode.FILE_INVALID.message());
    assertThatThrownBy(() -> FileRules.requireImage(new byte[0]))
        .hasMessage(ErrorCode.FILE_INVALID.message());
    assertThatThrownBy(() -> FileRules.requireImage(null))
        .hasMessage(ErrorCode.FILE_INVALID.message());
  }

  @Test
  void shouldRejectContentAboveTwoMegabytes() {
    byte[] pngHeader = Arrays.copyOf(PNG, 8);
    byte[] tooLarge = new byte[(int) FileRules.MAX_BYTES + 1];
    System.arraycopy(pngHeader, 0, tooLarge, 0, pngHeader.length);
    assertThatThrownBy(() -> FileRules.requireImage(tooLarge))
        .hasMessage(ErrorCode.FILE_TOO_LARGE.message());
  }
}
