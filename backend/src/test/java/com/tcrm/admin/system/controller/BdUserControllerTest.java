package com.tcrm.admin.system.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.service.BdUserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class BdUserControllerTest {
  private final BdUserService bdUsers = mock(BdUserService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new BdUserController(bdUsers));

  @Test
  void shouldReturnIdUsernameAndNicknameOnly_whenOperatorCalls() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.OPERATOR, DataScope.ALL);
    when(bdUsers.listSelectable())
        .thenReturn(List.of(new BdUserDTO(9007199254740993L, "bd1", "BD One", true)));
    mvc.perform(get("/admin-api/system/bd-users").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
        .andExpect(jsonPath("$.data[0].username").value("bd1"))
        .andExpect(jsonPath("$.data[0].nickname").value("BD One"))
        .andExpect(jsonPath("$.data[0].usable").doesNotExist());
  }

  @Test
  void shouldReturn403_whenFinanceCalls() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.FINANCE, DataScope.ALL);
    mvc.perform(get("/admin-api/system/bd-users").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(bdUsers);
  }
}
