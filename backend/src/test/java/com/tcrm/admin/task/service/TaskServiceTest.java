package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceTest {
  private static final long SUBMITTER = 7L;
  private final TaskMapper tasks = mock(TaskMapper.class);
  private final TaskFileMapper files = mock(TaskFileMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final TaskService service = new TaskService(tasks, files, current);

  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskFileDO.class);
  }

  @BeforeEach
  void setup() {
    TaskDO task = new TaskDO();
    task.setId(1L);
    task.setTaskType(TaskType.KOL_IMPORT);
    task.setStatus(TaskStatus.PARTIAL);
    task.setTotal(3);
    task.setDone(3);
    task.setFailed(1);
    task.setCreateBy(SUBMITTER);
    when(tasks.selectById(1L)).thenReturn(task);
  }

  private void actAs(long id, Role role, DataScope scope) {
    when(current.user())
        .thenReturn(new AccountDTO(id, "u" + id, "h", "n", null, role, scope, 1, null, null));
  }

  private static void assertError(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(code);
  }

  @Test
  void shouldReturnProgress_whenSubmitterAsks() {
    actAs(SUBMITTER, Role.OPERATOR, DataScope.OWN);
    when(files.selectCount(any())).thenReturn(1L);

    TaskDTO task = service.get(1L);

    assertThat(task.status()).isEqualTo(TaskStatus.PARTIAL);
    assertThat(task.failed()).isEqualTo(1);
    assertThat(task.hasErrorFile()).isTrue();
  }

  @Test
  void shouldReturnProgress_whenSuperAdministratorAsksForAnyTask() {
    actAs(99L, Role.SUPER_ADMIN, DataScope.ALL);
    when(files.selectCount(any())).thenReturn(0L);

    assertThat(service.get(1L).hasErrorFile()).isFalse();
  }

  @Test
  void shouldRejectWith403_whenAnotherOperatorAsks() {
    actAs(8L, Role.OPERATOR, DataScope.ALL);
    assertError(() -> service.get(1L), ErrorCode.AUTH_FORBIDDEN);
    assertError(() -> service.getErrorFile(1L), ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldRejectWith403_whenFinanceAsksForSomeoneElsesTask() {
    actAs(9L, Role.FINANCE, DataScope.ALL);
    assertError(() -> service.get(1L), ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldReturn404_whenTaskDoesNotExist() {
    actAs(SUBMITTER, Role.SUPER_ADMIN, DataScope.ALL);
    assertError(() -> service.get(2L), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldReturnErrorFile_whenSubmitterDownloads() {
    actAs(SUBMITTER, Role.OPERATOR, DataScope.ALL);
    TaskFileDO file = new TaskFileDO();
    file.setFileName("task-1-errors.xlsx");
    file.setContentType("application/x");
    file.setContent(new byte[] {1, 2});
    when(files.selectOne(any())).thenReturn(file);

    TaskFileDTO result = service.getErrorFile(1L);

    assertThat(result.fileName()).isEqualTo("task-1-errors.xlsx");
    assertThat(result.content()).containsExactly(1, 2);
  }

  @Test
  void shouldReturn404_whenTaskHasNoErrorFile() {
    actAs(SUBMITTER, Role.OPERATOR, DataScope.ALL);
    when(files.selectOne(any())).thenReturn(null);
    assertError(() -> service.getErrorFile(1L), ErrorCode.COMMON_NOT_FOUND);
  }
}
