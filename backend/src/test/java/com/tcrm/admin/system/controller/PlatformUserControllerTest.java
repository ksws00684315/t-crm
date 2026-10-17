package com.tcrm.admin.system.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.service.PlatformService;
import com.tcrm.admin.system.service.PlatformUserDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PlatformUserControllerTest {
  private final PlatformService platform = mock(PlatformService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new PlatformUserController(platform));

  private String admin() {
    return AdminMvcSupport.actAs(current, 1L, Role.PLATFORM_ADMIN, DataScope.ALL);
  }

  @Test
  void shouldFindUser_whenUsernameMatches() throws Exception {
    when(platform.findUser("alice"))
        .thenReturn(new PlatformUserDTO(3L, "alice", "Alice", null, 1, List.of()));
    mvc.perform(
            get("/admin-api/platform/users")
                .param("username", "alice")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
  }

  @Test
  void shouldEditPasswordAndStatus() throws Exception {
    mvc.perform(
            put("/admin-api/platform/users/3")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"Alice2\"}"))
        .andExpect(status().isOk());
    verify(platform).updateUser(3L, "Alice2", null);
    mvc.perform(
            put("/admin-api/platform/users/3/password")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Password12\"}"))
        .andExpect(status().isOk());
    verify(platform).resetPassword(3L, "Password12");
    mvc.perform(
            put("/admin-api/platform/users/3/status")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    verify(platform).updateUserStatus(3L, 0);
  }

  @Test
  void shouldReturn400_whenEditInvalid() throws Exception {
    mvc.perform(
            put("/admin-api/platform/users/3")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(platform);
  }

  @Test
  void shouldReturn403_whenNotPlatformAdmin() throws Exception {
    String token = AdminMvcSupport.actAs(current, 2L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/platform/users").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(platform);
  }
}
