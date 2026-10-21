package com.tcrm.admin.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.system.model.SystemConfigDefinition;
import com.tcrm.admin.system.service.SystemConfigService;
import org.junit.jupiter.api.Test;

class SystemConfigControllerTest {
  private final SystemConfigService configs = mock(SystemConfigService.class);
  private final SystemConfigController controller = new SystemConfigController(configs);

  @Test
  void shouldListEverySettingGroupedWithEffectiveAndDefaultValues() {
    when(configs.getValue(anyString()))
        .thenAnswer(invocation -> "value:" + invocation.getArgument(0));
    when(configs.getDefaultValue(anyString()))
        .thenAnswer(invocation -> "default:" + invocation.getArgument(0));
    when(configs.isOverridden(anyString())).thenReturn(false);

    var response = controller.list();

    assertThat(response.data()).hasSize(4);
    assertThat(response.data().stream().mapToInt(group -> group.items().size()).sum())
        .isEqualTo(SystemConfigDefinition.values().length);
    assertThat(response.data().getFirst().group()).isEqualTo("site");
    assertThat(response.data().getFirst().items().getFirst().value()).isEqualTo("value:site.name");
  }

  @Test
  void shouldUpdateAndResetRequestedKeys() {
    controller.update("site.name", new UpdateSystemConfigRequest("SparkTrack"));
    controller.reset("site.name");

    verify(configs).update("site.name", "SparkTrack");
    verify(configs).reset("site.name");
  }
}
