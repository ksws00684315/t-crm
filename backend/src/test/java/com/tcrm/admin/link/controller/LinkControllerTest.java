package com.tcrm.admin.link.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.link.service.LinkRebuildService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;

class LinkControllerTest {
  private final LinkRebuildService rebuilds = mock(LinkRebuildService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new LinkController(rebuilds));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  @Test
  void shouldAccept_whenSuperAdminTriggersRebuild() throws Exception {
    mvc.perform(
            post("/admin-api/link/index-rebuilds")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.code").value(0));
    verify(rebuilds).trigger();
  }

  @Test
  void shouldReturn409_whenRebuildAlreadyRunning() throws Exception {
    doThrow(new BusinessException(ErrorCode.LINK_REBUILD_RUNNING)).when(rebuilds).trigger();
    mvc.perform(
            post("/admin-api/link/index-rebuilds")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("LINK_REBUILD_RUNNING"));
  }

  @ParameterizedTest
  @CsvSource({"OPERATOR, ALL", "OPERATOR, OWN", "FINANCE, ALL"})
  void shouldReturn403_whenNonSuperAdminTriggersRebuild(Role role, DataScope scope)
      throws Exception {
    mvc.perform(
            post("/admin-api/link/index-rebuilds")
                .header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isForbidden());
    verify(rebuilds, never()).trigger();
  }

  @Test
  void shouldReturn401_whenNotLoggedIn() throws Exception {
    mvc.perform(post("/admin-api/link/index-rebuilds")).andExpect(status().isUnauthorized());
  }
}
