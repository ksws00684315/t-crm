package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.Test;

class ProductLinkMessagesTest {
  @Test
  void shouldReturnChineseMsg_forCopyTokenErrors() {
    BusinessException e = ProductLinkMessages.chinese(ErrorCode.COPY_TOKEN_INVALID);
    assertThat(e.hasCustomMessage()).isTrue();
    assertThat(e.getMessage()).isEqualTo("复制令牌无效或已作废");
    assertThat(
            ProductLinkMessages.wrap(new BusinessException(ErrorCode.COPY_TOKEN_EXPIRED))
                .getMessage())
        .isEqualTo("复制令牌已过期");
  }
}
