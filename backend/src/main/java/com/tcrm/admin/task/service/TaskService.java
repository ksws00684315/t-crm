package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskFileKind;
import com.tcrm.admin.task.model.TaskStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Import task progress and error files; access is restricted to the submitter or company super
 * admin.
 */
@Service
public class TaskService {
  private final TaskMapper tasks;
  private final TaskFileMapper files;
  private final CurrentUserContext current;
  private final BdUserApi users;

  public TaskService(
      TaskMapper tasks, TaskFileMapper files, CurrentUserContext current, BdUserApi users) {
    this.tasks = tasks;
    this.files = files;
    this.current = current;
    this.users = users;
  }

  public PageResult<TaskListDTO> page(TaskType type, TaskStatus status, int page, int size) {
    AccountDTO user = current.user();
    var result =
        tasks.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<TaskDO>()
                .eq(type != null, TaskDO::getTaskType, type)
                .eq(status != null, TaskDO::getStatus, status)
                .eq(user.role() != Role.SUPER_ADMIN, TaskDO::getCreateBy, user.id())
                .orderByDesc(TaskDO::getCreateTime, TaskDO::getId));
    List<TaskDO> rows = result.getRecords();
    if (rows.isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), page, size);
    }
    List<Long> ids = rows.stream().map(TaskDO::getId).toList();
    Map<Long, TaskFileDO> inputs = new HashMap<>();
    Set<Long> errors = new HashSet<>();
    for (TaskFileDO file :
        files.selectList(
            new LambdaQueryWrapper<TaskFileDO>()
                .select(TaskFileDO::getTaskId, TaskFileDO::getKind, TaskFileDO::getFileName)
                .in(TaskFileDO::getTaskId, ids))) {
      if (file.getKind() == TaskFileKind.INPUT) {
        inputs.put(file.getTaskId(), file);
      } else if (file.getKind() == TaskFileKind.ERROR) {
        errors.add(file.getTaskId());
      }
    }
    Set<Long> operatorIds =
        rows.stream().map(TaskDO::getCreateBy).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, String> names = new HashMap<>();
    for (BdUserDTO operator : users.listByIds(operatorIds)) {
      names.put(
          operator.id(),
          operator.nickname() == null || operator.nickname().isBlank()
              ? operator.username()
              : operator.nickname());
    }
    List<TaskListDTO> items = new ArrayList<>(rows.size());
    for (TaskDO task : rows) {
      TaskFileDO input = inputs.get(task.getId());
      int failed = task.getFailed() == null ? 0 : task.getFailed();
      int done = task.getDone() == null ? 0 : task.getDone();
      items.add(
          new TaskListDTO(
              task.getId(),
              task.getTaskType(),
              input == null ? null : input.getFileName(),
              task.getStatus(),
              task.getTotal() == null ? 0 : task.getTotal(),
              Math.max(0, done - failed),
              failed,
              task.getCreateTime(),
              task.getFinishedAt(),
              task.getCreateBy() == null ? null : names.get(task.getCreateBy()),
              errors.contains(task.getId())));
    }
    return new PageResult<>(items, result.getTotal(), page, size);
  }

  public TaskDTO get(Long id) {
    TaskDO task = requireAccessible(id);
    boolean hasErrorFile =
        files.selectCount(
                new LambdaQueryWrapper<TaskFileDO>()
                    .eq(TaskFileDO::getTaskId, id)
                    .eq(TaskFileDO::getKind, TaskFileKind.ERROR))
            > 0;
    return TaskDTO.from(task, hasErrorFile);
  }

  public TaskFileDTO getErrorFile(Long id) {
    TaskDO task = requireAccessible(id);
    TaskFileDO file =
        files.selectOne(
            new LambdaQueryWrapper<TaskFileDO>()
                .eq(TaskFileDO::getTaskId, id)
                .eq(TaskFileDO::getKind, TaskFileKind.ERROR));
    if (file == null) {
      if (task.getStatus() != null
          && task.getStatus().isFinished()
          && task.getFailed() != null
          && task.getFailed() > 0) {
        throw new BusinessException(ErrorCode.TASK_FILE_EXPIRED);
      }
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return new TaskFileDTO(file.getFileName(), file.getContentType(), file.getContent());
  }

  private TaskDO requireAccessible(Long id) {
    TaskDO task = tasks.selectById(id);
    if (task == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    AccountDTO user = current.user();
    if (user.role() != Role.SUPER_ADMIN && !user.id().equals(task.getCreateBy())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return task;
  }
}
