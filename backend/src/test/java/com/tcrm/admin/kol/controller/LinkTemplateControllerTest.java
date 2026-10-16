package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.tcrm.admin.kol.service.CreateLinkTemplateCommand;
import com.tcrm.admin.kol.service.EditLinkTemplateCommand;
import com.tcrm.admin.kol.service.LinkTemplateDTO;
import com.tcrm.admin.kol.service.LinkTemplateService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.Role;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class LinkTemplateControllerTest {
  private static final String BASE = "/admin-api/link/templates";
  private final LinkTemplateService templates = mock(LinkTemplateService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new LinkTemplateController(templates));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  private String staff(Set<String> permissions) {
    return AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL, permissions);
  }

  private static LinkTemplateDTO dto() {
    return new LinkTemplateDTO(
        77L,
        "kakobuy",
        "kakobuy",
        "https://item.kakobuy.com/i/{PRODUCT_ID}?aff={INVITE_CODE}",
        "WEIDIAN",
        0,
        1);
  }

  @Test
  void shouldListTemplates_whenStaffHasKolEdit() throws Exception {
    when(templates.list()).thenReturn(List.of(dto()));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, staff(Set.of("kol:edit"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("77"))
        .andExpect(jsonPath("$.data[0].templateCode").value("kakobuy"))
        .andExpect(jsonPath("$.data[0].channelCode").value("WEIDIAN"))
        .andExpect(jsonPath("$.data[0].sortOrder").value(0))
        .andExpect(jsonPath("$.data[0].status").value(1));
  }

  @Test
  void shouldReturn403OnRead_whenStaffLacksKolEdit() throws Exception {
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, staff(Set.of("kol:view"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(templates);
  }

  @Test
  void shouldCreate_whenSuperAdmin() throws Exception {
    when(templates.create(any())).thenReturn(dto());
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateCode\":\"kakobuy\",\"templateName\":\"kakobuy\","
                        + "\"templateUrl\":\"https://item.kakobuy.com/i/{PRODUCT_ID}\","
                        + "\"sortOrder\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.templateCode").value("kakobuy"));
    verify(templates)
        .create(
            new CreateLinkTemplateCommand(
                "kakobuy", "kakobuy", "https://item.kakobuy.com/i/{PRODUCT_ID}", 0));
  }

  @Test
  void shouldReturn403_whenStaffWrites() throws Exception {
    // Even every grantable code does not allow writing templates: super administrator only.
    String token = staff(Set.copyOf(Permission.codes()));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateCode\":\"k\",\"templateName\":\"k\","
                        + "\"templateUrl\":\"https://x/{PRODUCT_ID}\",\"sortOrder\":0}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/77")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateName\":\"k\",\"templateUrl\":\"https://x/{PRODUCT_ID}\","
                        + "\"sortOrder\":0}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/77/status")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(templates);
  }

  @Test
  void shouldReject400_whenBodyIsInvalid() throws Exception {
    String token = as(Role.SUPER_ADMIN, DataScope.ALL);
    for (String body :
        List.of(
            "{\"templateCode\":\"KAKO\",\"templateName\":\"k\","
                + "\"templateUrl\":\"https://x/{PRODUCT_ID}\",\"sortOrder\":0}",
            "{\"templateCode\":\"kakobuy\",\"templateName\":\"k\","
                + "\"templateUrl\":\"http://x/{PRODUCT_ID}\",\"sortOrder\":0}",
            "{\"templateCode\":\"kakobuy\",\"templateName\":\"k\","
                + "\"templateUrl\":\"https://x/\",\"sortOrder\":0}",
            "{\"templateCode\":\"kakobuy\",\"templateName\":\"k\","
                + "\"templateUrl\":\"https://x/{PRODUCT_ID}\"}",
            "{\"templateName\":\"k\",\"templateUrl\":\"https://x/{PRODUCT_ID}\",\"sortOrder\":0}")) {
      mvc.perform(
              post(BASE)
                  .header(AdminMvcSupport.TOKEN_HEADER, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(templates);
  }

  @Test
  void shouldEditAndChangeStatus_whenSuperAdmin() throws Exception {
    String token = as(Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            put(BASE + "/77")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"templateName\":\"kakobuy 2\","
                        + "\"templateUrl\":\"https://item.kakobuy.com/j/{PRODUCT_ID}\","
                        + "\"sortOrder\":5}"))
        .andExpect(status().isOk());
    verify(templates)
        .update(
            77L,
            new EditLinkTemplateCommand("kakobuy 2", "https://item.kakobuy.com/j/{PRODUCT_ID}", 5));
    mvc.perform(
            put(BASE + "/77/status")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isOk());
    verify(templates).updateStatus(77L, 0);
  }
}
