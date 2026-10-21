package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import com.tcrm.admin.system.model.SysUserDO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OperLogService {
  private final SysOperLogMapper logs;
  private final SysUserMapper users;
  private final ObjectMapper json;

  public OperLogService(SysOperLogMapper logs, SysUserMapper users, ObjectMapper json) {
    this.logs = logs;
    this.users = users;
    this.json = json;
  }

  public PageResult<OperLogDTO> page(OperLogQuery query, boolean platformAdmin) {
    if (platformAdmin) {
      return TenantScope.callSystem(
          "read platform system configuration audit records", () -> pageInScope(query, true));
    }
    return pageInScope(query, false);
  }

  private PageResult<OperLogDTO> pageInScope(OperLogQuery query, boolean platformScope) {
    var wrapper = new LambdaQueryWrapper<SysOperLogDO>();
    if (platformScope) {
      wrapper
          .isNull(SysOperLogDO::getTenantId)
          .eq(SysOperLogDO::getModule, "Platform")
          .likeRight(SysOperLogDO::getRequestUri, "/admin-api/platform/system-configs/");
    }
    if (hasText(query.module())) {
      wrapper.eq(SysOperLogDO::getModule, query.module().strip());
    }
    if (hasText(query.keyword())) {
      String keyword = query.keyword().strip();
      wrapper.and(
          nested ->
              nested
                  .like(SysOperLogDO::getAction, keyword)
                  .or()
                  .like(SysOperLogDO::getRequestParams, keyword));
    }
    if (hasText(query.operator())) {
      String operator = query.operator().strip();
      List<Long> userIds =
          users
              .selectList(
                  new LambdaQueryWrapper<SysUserDO>()
                      .select(SysUserDO::getId, SysUserDO::getUsername, SysUserDO::getNickname)
                      .and(
                          nested ->
                              nested
                                  .eq(SysUserDO::getUsername, operator)
                                  .or()
                                  .eq(SysUserDO::getNickname, operator)))
              .stream()
              .map(SysUserDO::getId)
              .toList();
      wrapper.and(
          nested -> {
            nested.eq(SysOperLogDO::getUsername, operator);
            if (!userIds.isEmpty()) {
              nested.or().in(SysOperLogDO::getUserId, userIds);
            }
          });
    }
    wrapper
        .ge(query.from() != null, SysOperLogDO::getOperTime, query.from())
        .le(query.to() != null, SysOperLogDO::getOperTime, query.to())
        .orderByDesc(SysOperLogDO::getOperTime, SysOperLogDO::getId);
    var result = logs.selectPage(new Page<>(query.page(), query.size()), wrapper);
    Map<Long, String> names = operatorNames(result.getRecords());
    return new PageResult<>(
        result.getRecords().stream().map(row -> toDto(row, names)).toList(),
        result.getTotal(),
        query.page(),
        query.size());
  }

  private Map<Long, String> operatorNames(List<SysOperLogDO> rows) {
    List<Long> ids =
        rows.stream().map(SysOperLogDO::getUserId).filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, String> result = new HashMap<>();
    for (SysUserDO user :
        users.selectList(
            new LambdaQueryWrapper<SysUserDO>()
                .select(SysUserDO::getId, SysUserDO::getUsername, SysUserDO::getNickname)
                .in(SysUserDO::getId, ids))) {
      result.put(
          user.getId(), hasText(user.getNickname()) ? user.getNickname() : user.getUsername());
    }
    return result;
  }

  private OperLogDTO toDto(SysOperLogDO row, Map<Long, String> names) {
    String summary = summary(row);
    String operatorName = row.getUserId() == null ? null : names.get(row.getUserId());
    if (!hasText(operatorName)) {
      operatorName = row.getUsername();
    }
    return new OperLogDTO(
        row.getId(),
        row.getModule(),
        row.getAction(),
        operatorName,
        row.getMethod(),
        row.getRequestUri(),
        summary,
        "SUCCESS".equalsIgnoreCase(row.getResult()),
        row.getOperTime(),
        row.getIp(),
        row.getUserId());
  }

  private String summary(SysOperLogDO row) {
    if (hasText(row.getRequestParams())) {
      try {
        JsonNode node = json.readTree(row.getRequestParams());
        JsonNode summary = node.get("summary");
        if (summary != null && summary.isTextual() && hasText(summary.asText())) {
          return summary.asText();
        }
      } catch (Exception ignored) {
        // Existing audit records may contain non-JSON summaries.
        return actionSummary(row.getAction());
      }
    }
    return actionSummary(row.getAction());
  }

  private String actionSummary(String action) {
    return switch (action == null ? "" : action) {
      case "Create BD transfer" -> "创建达人转移";
      case "Execute BD transfer" -> "执行预约转移";
      case "Cancel BD transfer", "Cancel scheduled BD transfer" -> "取消预约转移";
      case "Create" -> "新增记录";
      case "Edit" -> "编辑记录";
      case "Change status" -> "修改状态";
      case "Review KOL" -> "审核达人";
      case "Save promotion config" -> "保存推广配置";
      case "Set KOL auto cleanup" -> "设置达人自动清理";
      case "Update post template" -> "修改岗位模板";
      case "Reset post template" -> "恢复岗位模板默认值";
      case "Self register" -> "达人自助注册";
      case "Manual KOL cleanup" -> "手动清理达人";
      default -> hasText(action) ? "其他操作" : "操作记录";
    };
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
