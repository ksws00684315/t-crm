package com.tcrm.admin.system.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.PlatformService;
import com.tcrm.admin.system.service.TenantAdminDTO;
import com.tcrm.admin.system.service.TenantDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PlatformControllerTest {
  private final PlatformService platform = mock(PlatformService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new PlatformController(platform));

  private String admin() {
    return AdminMvcSupport.actAs(current, 1L, Role.PLATFORM_ADMIN, DataScope.ALL);
  }

  @Test
  void shouldListTenants_whenPlatformAdminCalls() throws Exception {
    when(platform.list())
        .thenReturn(List.of(new TenantDTO(1L, "default", "Default", 1, null, null)));
    mvc.perform(get("/admin-api/platform/tenants").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].tenantCode").value("default"));
  }

  @Test
  void shouldCreateTenant_whenRequestValid() throws Exception {
    when(platform.create(eq("acme"), eq("Acme"), eq(null)))
        .thenReturn(new TenantDTO(2L, "acme", "Acme", 1, null, null));
    mvc.perform(
            post("/admin-api/platform/tenants")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantCode\":\"acme\",\"name\":\"Acme\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("2"));
  }

  @Test
  void shouldReturn400_whenCreateTenantInvalid() throws Exception {
    mvc.perform(
            post("/admin-api/platform/tenants")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantCode\":\"\",\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(platform);
  }

  @Test
  void shouldReturn403_whenCompanyAccountCalls() throws Exception {
    String token = AdminMvcSupport.actAs(current, 2L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/platform/tenants").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(platform);
  }

  @Test
  void shouldEditStatusAndListAdmins() throws Exception {
    when(platform.listAdmins(1L)).thenReturn(List.of());
    mvc.perform(
            put("/admin-api/platform/tenants/1")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk());
    verify(platform).update(1L, "Renamed", null);
    mvc.perform(
            put("/admin-api/platform/tenants/1/status")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    verify(platform).updateStatus(1L, 0);
    mvc.perform(
            get("/admin-api/platform/tenants/1/admins")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk());
    verify(platform).listAdmins(1L);
  }

  @Test
  void shouldCreateAdminAndResetPassword() throws Exception {
    when(platform.createAdmin(eq(1L), eq("boss"), any(), eq("Boss"), eq(null)))
        .thenReturn(new TenantAdminDTO(9L, "boss", "Boss", null, 1, null, null));
    mvc.perform(
            post("/admin-api/platform/tenants/1/admins")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"boss\",\"password\":\"Password12\",\"nickname\":\"Boss\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("boss"));
    mvc.perform(
            put("/admin-api/platform/tenants/1/admins/9/password")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Password99\"}"))
        .andExpect(status().isOk());
    verify(platform).resetAdminPassword(1L, 9L, "Password99");
  }
}
