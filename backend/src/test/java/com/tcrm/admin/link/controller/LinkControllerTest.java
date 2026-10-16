package com.tcrm.admin.link.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.link.model.LinkUnavailableReason;
import com.tcrm.admin.link.service.LinkDTO;
import com.tcrm.admin.link.service.LinkFilterDTO;
import com.tcrm.admin.link.service.LinkRebuildService;
import com.tcrm.admin.link.service.LinkService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

class LinkControllerTest {
  private final LinkService links = mock(LinkService.class);
  private final LinkRebuildService rebuilds = mock(LinkRebuildService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new LinkController(links, rebuilds));
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
  @CsvSource({"STAFF, ALL", "STAFF, OWN"})
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

  private static PageResult<LinkDTO> onePage() {
    return new PageResult<>(
        List.of(
            new LinkDTO(
                5L,
                "P000001",
                "Hoodie",
                "9001",
                "https://t.example.com/t/KOL0001/P000001",
                "https://weidian.com/item.html?itemID=9001",
                false,
                List.of(LinkUnavailableReason.KOL_DISABLED, LinkUnavailableReason.SHOP_DISABLED),
                12,
                7)),
        1,
        1,
        20);
  }

  /** Task 06 §4.7: every role may call the list; the BD data scope is enforced by the service. */
  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldListLinks_forEveryRole(Role role, DataScope scope) throws Exception {
    when(links.page(any())).thenReturn(onePage());

    mvc.perform(
            get("/admin-api/link/links")
                .param("kolCode", "KOL0001")
                .header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].productCode").value("P000001"))
        .andExpect(
            jsonPath("$.data.items[0].linkUrl").value("https://t.example.com/t/KOL0001/P000001"))
        .andExpect(jsonPath("$.data.items[0].available").value(false))
        .andExpect(jsonPath("$.data.items[0].unavailableReasons[1]").value("SHOP_DISABLED"))
        .andExpect(jsonPath("$.data.items[0].clicks30d").value(12))
        .andExpect(jsonPath("$.data.items[0].validClicks30d").value(7));
  }

  @Test
  void shouldPassFilters_whenListingLinks() throws Exception {
    when(links.page(any())).thenReturn(onePage());

    mvc.perform(
            get("/admin-api/link/links")
                .param("kolCode", " KOL0001 ")
                .param("productCode", "P000001")
                .param("page", "2")
                .param("size", "50")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isOk());

    ArgumentCaptor<LinkFilterDTO> filter = ArgumentCaptor.forClass(LinkFilterDTO.class);
    verify(links).page(filter.capture());
    assertThat(filter.getValue()).isEqualTo(new LinkFilterDTO("KOL0001", "P000001", 2, 50));
  }

  @Test
  void shouldReturn400_whenKolCodeMissing() throws Exception {
    mvc.perform(
            get("/admin-api/link/links")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/admin-api/link/links")
                .param("kolCode", " ")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest());
    verify(links, never()).page(any());
  }

  @Test
  void shouldReturn403_whenBdListsOtherKolLinks() throws Exception {
    when(links.page(any())).thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN));
    mvc.perform(
            get("/admin-api/link/links")
                .param("kolCode", "KOL0002")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.OWN)))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn401_whenListingWithoutLogin() throws Exception {
    mvc.perform(get("/admin-api/link/links").param("kolCode", "KOL0001"))
        .andExpect(status().isUnauthorized());
  }
}
