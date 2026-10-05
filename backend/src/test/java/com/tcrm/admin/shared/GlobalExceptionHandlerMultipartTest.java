package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

class GlobalExceptionHandlerMultipartTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void shouldReturnFileTooLarge_whenUploadExceedsMultipartLimit() {
    var response = handler.uploadTooLarge(new MaxUploadSizeExceededException(1L));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().errorCode()).isEqualTo("TASK_FILE_TOO_LARGE");
  }

  @Test
  void shouldReturnValidationFailure_whenFilePartIsMissingOrMalformed() {
    assertThat(
            handler
                .invalidMultipart(new MissingServletRequestPartException("file"))
                .getBody()
                .errorCode())
        .isEqualTo("COMMON_VALIDATION_FAILED");
    assertThat(handler.invalidMultipart(new MultipartException("bad")).getStatusCode().value())
        .isEqualTo(400);
  }
}
