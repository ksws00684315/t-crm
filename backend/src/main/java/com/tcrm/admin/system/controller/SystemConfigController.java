package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.model.SystemConfigDefinition;
import com.tcrm.admin.system.service.SystemConfigService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform administrator configuration maintenance. */
@RestController
@RequestMapping("/admin-api/platform/system-configs")
@SaCheckRole("PLATFORM_ADMIN")
public class SystemConfigController {
  private final SystemConfigService configs;

  public SystemConfigController(SystemConfigService configs) {
    this.configs = configs;
  }

  @GetMapping
  public R<List<SystemConfigGroupVO>> list() {
    Map<String, List<SystemConfigVO>> groups = new LinkedHashMap<>();
    for (SystemConfigDefinition definition : SystemConfigDefinition.values()) {
      groups
          .computeIfAbsent(definition.group(), ignored -> new java.util.ArrayList<>())
          .add(
              SystemConfigVO.from(
                  definition,
                  configs.getValue(definition.key()),
                  configs.getDefaultValue(definition.key()),
                  configs.isOverridden(definition.key())));
    }
    return R.ok(
        groups.entrySet().stream()
            .map(entry -> new SystemConfigGroupVO(entry.getKey(), List.copyOf(entry.getValue())))
            .toList());
  }

  @PutMapping("/{key}")
  public R<Void> update(
      @PathVariable String key, @Valid @RequestBody UpdateSystemConfigRequest request) {
    configs.update(key, request.value());
    return R.ok(null);
  }

  @DeleteMapping("/{key}")
  public R<Void> reset(@PathVariable String key) {
    configs.reset(key);
    return R.ok(null);
  }
}
