package com.tcrm.admin.kol.controller;

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
import com.tcrm.admin.kol.service.KolImportService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class KolImportControllerTest {
  private static final String BASE = "/admin-api/kol/kol-imports";
  private final KolImportService imports = mock(KolImportService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
  private final MockMvc mvc;

  {
    messages.setBasenames("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    mvc = AdminMvcSupport.build(current, new KolImportController(imports, messages));
  }

  private final MockMultipartFile file =
      new MockMultipartFile("file", "达人.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1, 2});

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldAcceptUploadWith202AndTaskId_whenImporterSubmits(Role role, DataScope scope)
      throws Exception {
    when(imports.submit(any())).thenReturn(9007199254740993L);
    String token = AdminMvcSupport.actAs(current, 1L, role, scope);
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.taskId").value("9007199254740993"));
    var dto = ArgumentCaptor.forClass(ImportFileDTO.class);
    verify(imports).submit(dto.capture());
    assertThat(dto.getValue().fileName()).isEqualTo("达人.xlsx");
    assertThat(dto.getValue().contentType()).isEqualTo(ImportTaskApi.XLSX_CONTENT_TYPE);
    assertThat(dto.getValue().content()).containsExactly(1, 2);
  }

  @Test
  void shouldReturn403_whenStaffLacksKolEdit() throws Exception {
    String token =
        AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL, java.util.Set.of("kol:view"));
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE + "/template").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(imports);
  }

  @Test
  void shouldReturn400_whenFilePartIsMissing() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            multipart(BASE)
                .file(new MockMultipartFile("other", new byte[] {1}))
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(imports);
  }

  @Test
  void shouldMapFileErrors_whenServiceRejectsFile() throws Exception {
    when(imports.submit(any()))
        .thenThrow(new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH))
        .thenThrow(new BusinessException(ErrorCode.TASK_QUEUE_FULL));
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_HEADER_MISMATCH"));
    mvc.perform(multipart(BASE).file(file).header(AdminMvcSupport.TOKEN_HEADER, token))
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
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(multipart(BASE).file(broken).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("TASK_FILE_INVALID"));
    verifyNoInteractions(imports);
  }

  @Test
  void shouldDownloadEnglishTemplateName_whenRequestIsEnglish() throws Exception {
    when(imports.template()).thenReturn(new byte[] {'P', 'K'});
    String token = AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.OWN);
    mvc.perform(
            get(BASE + "/template")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .header("Accept-Language", "en-US"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", ImportTaskApi.XLSX_CONTENT_TYPE))
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("kol-import-template.xlsx")))
        .andExpect(content().bytes(new byte[] {'P', 'K'}));
  }

  @Test
  void shouldDownloadChineseTemplateName_whenRequestIsChinese() throws Exception {
    when(imports.template()).thenReturn(new byte[] {'P', 'K'});
    String token = AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.OWN);
    // Non-ASCII file names are sent as the RFC 5987 filename* parameter.
    String encoded =
        java.net.URLEncoder.encode("达人导入模板.xlsx", java.nio.charset.StandardCharsets.UTF_8);
    mvc.perform(
            get(BASE + "/template")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .header("Accept-Language", "zh-CN"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString(encoded)));
  }
}
