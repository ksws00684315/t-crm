package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.KolItemService;
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
import org.springframework.test.web.servlet.MockMvc;

class KolItemControllerTest {
  private final KolItemService items = mock(KolItemService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = AdminMvcSupport.build(current, new KolItemController(items));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "OPERATOR, ALL", "FINANCE, ALL"})
  void shouldList_whenAllowedRoleReads(Role role, DataScope scope) throws Exception {
    when(items.page(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(
            get("/admin-api/product/kol-items")
                .header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk());
    verify(items).page(any());
  }

  @Test
  void shouldReturn403_whenOwnOperatorReads() throws Exception {
    doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN)).when(items).page(any());
    mvc.perform(
            get("/admin-api/product/kol-items")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.OPERATOR, DataScope.OWN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }
}
