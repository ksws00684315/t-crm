package com.tcrm.admin.system.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.shared.GlobalExceptionHandler;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.service.OperLogQuery;
import com.tcrm.admin.system.service.OperLogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperLogControllerTest {
  private final OperLogService logs = mock(OperLogService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new OperLogController(logs))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void shouldRejectPageBeyondLimit_whenListingOperLogs() throws Exception {
    mvc.perform(get("/admin-api/system/oper-logs?page=10001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(logs);
  }

  @Test
  void shouldAcceptLastAllowedPage_whenListingOperLogs() throws Exception {
    when(logs.page(any(OperLogQuery.class))).thenReturn(new PageResult<>(List.of(), 0, 10000, 20));
    mvc.perform(get("/admin-api/system/oper-logs?page=10000")).andExpect(status().isOk());
    verify(logs).page(new OperLogQuery(null, null, null, null, null, 10000, 20));
  }

  @Test
  void shouldRejectOversizedPage_whenSizeExceedsHundred() throws Exception {
    mvc.perform(get("/admin-api/system/oper-logs?size=101")).andExpect(status().isBadRequest());
    verify(logs, never()).page(any(OperLogQuery.class));
  }
}
