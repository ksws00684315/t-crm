package com.tcrm.admin.system.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.UserPermissionService;
import com.tcrm.admin.system.service.UserPermissionService.PermissionCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class PermissionControllerTest {
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new PermissionController(permissions));

  @Test
  void shouldReturnCatalog_whenSuperAdminCalls() throws Exception {
    when(permissions.catalog()).thenReturn(new PermissionCatalog(List.of(), List.of()));
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/system/permissions").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions").isArray());
    verify(permissions).catalog();
  }

  @Test
  void shouldReturn403_whenStaffCalls() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL);
    mvc.perform(get("/admin-api/system/permissions").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(permissions);
  }

  @Test
  void shouldReturn401_whenAnonymous() throws Exception {
    mvc.perform(get("/admin-api/system/permissions")).andExpect(status().isUnauthorized());
    verifyNoInteractions(permissions);
  }
}
