package com.tcrm.admin.order.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.order.service.IdleKolCleanupService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class IdleKolCleanupControllerTest {
  private final IdleKolCleanupService cleanup = mock(IdleKolCleanupService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new IdleKolCleanupController(cleanup));

  private String token(Set<String> permissions) {
    return AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.OWN, permissions);
  }

  @Test
  void shouldKeepManualCleanup_forStaffWithEdit() throws Exception {
    mvc.perform(
            post("/admin-api/kol/kols/10/cleanup")
                .header(AdminMvcSupport.TOKEN_HEADER, token(Set.of("kol:edit")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual cleanup\"}"))
        .andExpect(status().isOk());
    verify(cleanup).cleanupManual(10L, "manual cleanup");
  }

  @Test
  void shouldRemoveCleanupItemListAndRetryEndpoints() throws Exception {
    mvc.perform(post("/admin-api/kol/cleanup-items/9/retry")).andExpect(status().isNotFound());
    mvc.perform(get("/admin-api/kol/cleanup-items")).andExpect(status().isNotFound());
    verifyNoInteractions(cleanup);
  }

  @Test
  void shouldRejectManualCleanupWithoutEditPermission() throws Exception {
    mvc.perform(
            post("/admin-api/kol/kols/10/cleanup")
                .header(AdminMvcSupport.TOKEN_HEADER, token(Set.of("kol:view")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual cleanup\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(cleanup);
  }
}
