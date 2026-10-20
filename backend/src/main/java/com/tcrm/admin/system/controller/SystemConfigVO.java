package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.model.SystemConfigDefinition;

public record SystemConfigVO(
    String key,
    String group,
    String type,
    String value,
    String defaultValue,
    boolean overridden,
    Integer min,
    Integer max,
    String nameKey) {
  static SystemConfigVO from(
      SystemConfigDefinition definition, String value, String defaultValue, boolean overridden) {
    return new SystemConfigVO(
        definition.key(),
        definition.group(),
        definition.type(),
        value,
        defaultValue,
        overridden,
        definition.min(),
        definition.max(),
        definition.nameKey());
  }
}
