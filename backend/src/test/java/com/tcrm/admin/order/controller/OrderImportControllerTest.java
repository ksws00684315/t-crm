package com.tcrm.admin.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.order.service.OrderImportService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class OrderImportControllerTest {
  private static final String BASE = "/admin-api/order/order-imports";
  private static final String CUTOFF = "2026-10-21T00:00:00Z";
  private final OrderImportService imports = mock(OrderImportService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
  private final MockMvc mvc;

  {
    messages.setBasenames("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    mvc = AdminMvcSupport.build(current, new OrderImportController(imports, messages));
  }

  private final MockMultipartFile file =
      new MockMultipartFile("file", "imp.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1, 2});

  private String manager() {
    return AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL, Set.of("order:manage"));
  }

  @Test
  void shouldAcceptUploadWith202AndTaskId_whenManagerSubmits() throws Exception {
    when(imports.submit(any(), any())).thenReturn(9007199254740993L);
    mvc.perform(
            multipart(BASE)
                .file(file)
                .param("dataCutoffTime", CUTOFF)
                .header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.taskId").value("9007199254740993"));
    var dto = ArgumentCaptor.forClass(ImportFileDTO.class);
    var cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(imports).submit(dto.capture(), cutoff.capture());
    assertThat(dto.getValue().fileName()).isEqualTo("imp.xlsx");
    assertThat(cutoff.getValue()).isEqualTo(Instant.parse(CUTOFF));
  }

  @Test
  void shouldReturn403_whenStaffLacksOrderManage() throws Exception {
    String token =
        AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL, Set.of("order:view"));
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE + "/template").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(imports);
  }

  @Test
  void shouldReturn400_whenDataCutoffTimeIsMissing() throws Exception {
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(imports);
  }

  @Test
  void shouldReturn400_whenFilePartIsMissing() throws Exception {
    mvc.perform(
            multipart(BASE)
                .file(new MockMultipartFile("other", new byte[] {1}))
                .header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(imports);
  }

  @Test
  void shouldMapFileErrors_whenServiceRejectsFile() throws Exception {
    when(imports.submit(any(), any()))
        .thenThrow(new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH))
        .thenThrow(new BusinessException(ErrorCode.TASK_QUEUE_FULL));
    mvc.perform(
            multipart(BASE)
                .file(file)
                .param("dataCutoffTime", CUTOFF)
                .header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_HEADER_MISMATCH"));
    mvc.perform(
            multipart(BASE)
                .file(file)
                .param("dataCutoffTime", CUTOFF)
                .header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("TASK_QUEUE_FULL"));
  }

  @Test
  void shouldReturn400_whenUploadCannotBeRead() throws Exception {
    MockMultipartFile broken =
        new MockMultipartFile("file", "k.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1}) {
          @Override
          public byte[] getBytes() throws IOException {
            throw new IOException("unit");
          }
        };
    mvc.perform(
            multipart(BASE)
                .file(broken)
                .param("dataCutoffTime", CUTOFF)
                .header(AdminMvcSupport.TOKEN_HEADER, manager()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_INVALID"));
    verifyNoInteractions(imports);
  }

  @Test
  void shouldDownloadTemplate_whenManagerRequests() throws Exception {
    when(imports.template()).thenReturn(new byte[] {'P', 'K'});
    mvc.perform(
            get(BASE + "/template")
                .header(AdminMvcSupport.TOKEN_HEADER, manager())
                .header("Accept-Language", "en-US"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", ImportTaskApi.XLSX_CONTENT_TYPE))
        .andExpect(content().bytes(new byte[] {'P', 'K'}));
  }
}
