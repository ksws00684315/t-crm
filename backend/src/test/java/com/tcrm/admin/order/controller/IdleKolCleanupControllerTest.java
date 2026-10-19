package com.tcrm.admin.order.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.order.service.IdleKolCleanupService;
import com.tcrm.admin.product.api.KolCleanupItemApi.KolCleanupItemDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class IdleKolCleanupControllerTest {
  private final IdleKolCleanupService cleanup = mock(IdleKolCleanupService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new IdleKolCleanupController(cleanup));

  private String viewOnlyToken() {
    return AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.OWN, Set.of("kol:view"));
  }

  private String editToken() {
    return AdminMvcSupport.actAs(
        current, 1L, Role.STAFF, DataScope.OWN, Set.of("kol:edit", "kol:view"));
  }

  @Test
  void shouldAllowViewOnlyStaff_toListCleanupItems() throws Exception {
    when(cleanup.pageCleanupItems(null, null, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new KolCleanupItemDTO(
                        9L,
                        10L,
                        "KOL0010",
                        1L,
                        100L,
                        5L,
                        "Shop A",
                        "wd-1",
                        "PENDING",
                        "MANUAL",
                        "idle",
                        null,
                        Instant.parse("2026-10-19T00:00:00Z"),
                        null)),
                1,
                1,
                20));
    mvc.perform(
            get("/admin-api/kol/cleanup-items")
                .header(AdminMvcSupport.TOKEN_HEADER, viewOnlyToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL0010"))
        .andExpect(jsonPath("$.data.items[0].shopName").value("Shop A"));
  }

  @Test
  void shouldReturn403_whenViewOnlyStaffWritesCleanup() throws Exception {
    String token = viewOnlyToken();
    mvc.perform(
            post("/admin-api/kol/kols/10/cleanup")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual cleanup\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin-api/kol/cleanup-items/9/retry")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(cleanup);
  }

  @Test
  void shouldRetryCleanupItem_whenStaffHasEdit() throws Exception {
    mvc.perform(
            post("/admin-api/kol/cleanup-items/9/retry")
                .header(AdminMvcSupport.TOKEN_HEADER, editToken()))
        .andExpect(status().isOk());
    verify(cleanup).retryCleanupItem(9L);
  }
}
