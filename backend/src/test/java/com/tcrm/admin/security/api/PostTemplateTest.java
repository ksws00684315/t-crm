package com.tcrm.admin.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostTemplateTest {

  @Test
  void operatorTemplateDoesNotGrantCreatorEditing() {
    assertThat(PostTemplate.OPERATOR.permissionCodes())
        .doesNotContain("kol:edit")
        .contains("kol:view", "product:view", "order:view");
  }
}
