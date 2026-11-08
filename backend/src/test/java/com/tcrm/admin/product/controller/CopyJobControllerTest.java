package com.tcrm.admin.product.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.CopyDailyQuota;
import com.tcrm.admin.product.service.CopyJobQueryService;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class CopyJobControllerTest {
  private static final String BASE = "/admin-api/product/copy-jobs";
  private final CopyJobQueryService jobs = mock(CopyJobQueryService.class);
  private final CopyDailyQuota dailyQuota = mock(CopyDailyQuota.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new CopyJobController(jobs, dailyQuota, current));
  }

  @Test
  void shouldReturn400_whenItemJobIdIsNotPositive() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get(BASE + "/0/items").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(jobs);
  }

  @Test
  void shouldReturnTodaysUsage_ofTheLoggedInCompany() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    AccountDTO u = current.user();
    when(current.user())
        .thenReturn(
            new AccountDTO(
                u.id(),
                u.username(),
                u.passwordHash(),
                u.nickname(),
                u.email(),
                u.role(),
                u.dataScope(),
                u.status(),
                u.lastLoginTime(),
                u.createTime(),
                u.credentialVersion(),
                7L,
                "Company 7",
                true,
                u.permissions(),
                u.postTemplate()));
    when(dailyQuota.usage(7L)).thenReturn(new CopyDailyQuota.Usage(4589, 5000));
    mvc.perform(get(BASE + "/daily-quota").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.used").value(4589))
        .andExpect(jsonPath("$.data.limit").value(5000));
  }
}
