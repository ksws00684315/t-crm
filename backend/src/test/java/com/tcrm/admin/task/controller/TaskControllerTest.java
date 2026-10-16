package com.tcrm.admin.task.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.model.TaskStatus;
import com.tcrm.admin.task.service.TaskDTO;
import com.tcrm.admin.task.service.TaskFileDTO;
import com.tcrm.admin.task.service.TaskService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;

class TaskControllerTest {
  private final TaskService tasks = mock(TaskService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new TaskController(tasks));

  private static TaskDTO task() {
    return new TaskDTO(
        9007199254740993L,
        TaskType.KOL_IMPORT,
        TaskStatus.PARTIAL,
        3,
        3,
        1,
        null,
        true,
        Instant.parse("2026-10-05T00:00:00Z"),
        Instant.parse("2026-10-05T00:00:01Z"),
        Instant.parse("2026-10-05T00:00:02Z"));
  }

  @ParameterizedTest
  @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "PLATFORM_ADMIN")
  void shouldReturnProgress_whenAnyCompanyRoleCalls(Role role) throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, role, DataScope.ALL);
    when(tasks.get(5L)).thenReturn(task());

    mvc.perform(get("/admin-api/task/tasks/5").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("9007199254740993"))
        .andExpect(jsonPath("$.data.status").value("PARTIAL"))
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.failed").value(1))
        .andExpect(jsonPath("$.data.hasErrorFile").value(true))
        .andExpect(jsonPath("$.data.finishedAt").value("2026-10-05T00:00:02Z"));
  }

  @Test
  void shouldReturn403_whenCallerIsPlatformAdmin() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.PLATFORM_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/task/tasks/5").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(tasks);
  }

  @Test
  void shouldReturn403_whenServiceDeniesAccess() throws Exception {
    String token = AdminMvcSupport.actAs(current, 2L, Role.STAFF, DataScope.OWN);
    when(tasks.get(5L)).thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN));

    mvc.perform(get("/admin-api/task/tasks/5").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldReturn401_whenNotLoggedIn() throws Exception {
    mvc.perform(get("/admin-api/task/tasks/5")).andExpect(status().isUnauthorized());
    verifyNoInteractions(tasks);
  }

  @Test
  void shouldRejectInvalidId_whenIdIsNotPositive() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/task/tasks/0").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(tasks);
  }

  @Test
  void shouldDownloadAttachment_whenErrorFileExists() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL);
    when(tasks.getErrorFile(5L))
        .thenReturn(
            new TaskFileDTO(
                "task-5-errors.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1, 2, 3}));

    mvc.perform(
            get("/admin-api/task/tasks/5/error-file").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString("task-5")))
        .andExpect(content().contentType(ImportTaskApi.XLSX_CONTENT_TYPE))
        .andExpect(content().bytes(new byte[] {1, 2, 3}));
  }

  @Test
  void shouldReturnJson404_whenErrorFileMissing() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL);
    when(tasks.getErrorFile(anyLong()))
        .thenThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND));

    mvc.perform(
            get("/admin-api/task/tasks/5/error-file").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }
}
