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
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.service.UserDTO;
import com.tcrm.admin.system.service.UserPermissionService;
import com.tcrm.admin.system.service.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class UserControllerTest {
  private final UserService users = mock(UserService.class);
  private final UserPermissionService permissions = mock(UserPermissionService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new UserController(users, permissions));

  private String admin() {
    return AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
  }

  private static UserDTO sample() {
    return new UserDTO(
        5L, "staff1", "Staff", null, Role.STAFF, DataScope.OWN, 1, null, null, null, List.of(), 1);
  }

  @Test
  void shouldPageUsers_whenSuperAdminCalls() throws Exception {
    when(users.page(1, 20, null, null, null))
        .thenReturn(new PageResult<>(List.of(sample()), 1, 1, 20));
    mvc.perform(get("/admin-api/system/users").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].username").value("staff1"));
  }

  @Test
  void shouldCreateUser_whenRequestValid() throws Exception {
    when(users.create(any())).thenReturn(sample());
    mvc.perform(
            post("/admin-api/system/users")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"staff1\",\"password\":\"Password12\",\"nickname\":\"Staff\",\"role\":\"STAFF\",\"dataScope\":\"OWN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("5"));
  }

  @Test
  void shouldReturn400_whenCreateInvalid() throws Exception {
    mvc.perform(
            post("/admin-api/system/users")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"a\",\"password\":\"short\",\"nickname\":\"\",\"role\":\"STAFF\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(users);
  }

  @Test
  void shouldEditStatusPasswordAndPermissions() throws Exception {
    when(permissions.get(5L))
        .thenReturn(
            new UserPermissionService.UserPermissionView(List.of("kol:view"), DataScope.OWN));
    mvc.perform(
            put("/admin-api/system/users/5")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"S2\",\"role\":\"STAFF\",\"dataScope\":\"OWN\"}"))
        .andExpect(status().isOk());
    verify(users).update(eq(5L), any());
    mvc.perform(
            put("/admin-api/system/users/5/status")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    verify(users).updateStatus(5L, 0);
    mvc.perform(
            put("/admin-api/system/users/5/password")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Password99\"}"))
        .andExpect(status().isOk());
    verify(users).updatePassword(5L, "Password99");
    mvc.perform(
            get("/admin-api/system/users/5/permissions")
                .header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin-api/system/users/5/permissions")
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"kol:view\"],\"dataScope\":\"OWN\"}"))
        .andExpect(status().isOk());
    verify(permissions).replace(5L, List.of("kol:view"), DataScope.OWN);
  }

  @Test
  void shouldReturn403_whenStaffCalls() throws Exception {
    String token = AdminMvcSupport.actAs(current, 2L, Role.STAFF, DataScope.ALL);
    mvc.perform(get("/admin-api/system/users").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(users, permissions);
  }
}
