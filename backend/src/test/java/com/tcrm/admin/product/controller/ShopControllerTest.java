package com.tcrm.admin.product.controller;

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
import com.tcrm.admin.product.service.ShopDTO;
import com.tcrm.admin.product.service.ShopService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ShopControllerTest {
  private static final String BASE = "/admin-api/product/shops";
  private final ShopService shops = mock(ShopService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new ShopController(shops));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "OPERATOR, ALL", "OPERATOR, OWN", "FINANCE, ALL"})
  void shouldList_whenAnyRoleReads(Role role, DataScope scope) throws Exception {
    when(shops.list())
        .thenReturn(
            List.of(
                new ShopDTO(
                    1L,
                    "店",
                    "100",
                    1,
                    null,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:00Z"))));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].weidianShopId").value("100"));
  }

  @Test
  void shouldCreate_whenSuperAdmin() throws Exception {
    when(shops.create(any()))
        .thenReturn(
            new ShopDTO(
                1L,
                "店",
                "100",
                1,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopName\":\"店\",\"weidianShopId\":\"100\"}"))
        .andExpect(status().isOk());
    verify(shops).create(any());
  }

  @ParameterizedTest
  @CsvSource({"OPERATOR, ALL", "OPERATOR, OWN", "FINANCE, ALL"})
  void shouldReturn403_whenNonSuperAdminWrites(Role role, DataScope scope) throws Exception {
    String token = as(role, scope);
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopName\":\"店\",\"weidianShopId\":\"100\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/1")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopName\":\"店\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/1/status")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(shops);
  }
}
