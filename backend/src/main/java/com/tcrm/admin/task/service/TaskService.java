package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskFileKind;
import org.springframework.stereotype.Service;

/** Task progress and error files; only the submitter or a super administrator may read them. */
@Service
public class TaskService {
  private final TaskMapper tasks;
  private final TaskFileMapper files;
  private final CurrentUserContext current;

  public TaskService(TaskMapper tasks, TaskFileMapper files, CurrentUserContext current) {
    this.tasks = tasks;
    this.files = files;
    this.current = current;
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
      // Finished tasks with failures once had an error file; after 30-day cleanup it is gone.
      if (task.getStatus().isFinished() && task.getFailed() != null && task.getFailed() > 0) {
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
