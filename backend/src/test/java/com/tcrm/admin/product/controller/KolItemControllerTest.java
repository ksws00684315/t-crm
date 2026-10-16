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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

  private MockHttpServletRequestBuilder list(String token) {
    return get("/admin-api/product/kol-items").header(AdminMvcSupport.TOKEN_HEADER, token);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL"})
  void shouldList_whenAllowedRoleReadsWithKolCode(Role role, DataScope scope) throws Exception {
    when(items.page(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(list(as(role, scope)).param("kolCode", "KOL0001")).andExpect(status().isOk());
    verify(items).page(any());
  }

  @Test
  void shouldReturn403_whenOwnOperatorReads() throws Exception {
    doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN)).when(items).page(any());
    mvc.perform(list(as(Role.STAFF, DataScope.OWN)).param("kolCode", "KOL0001"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldReturn400_whenAllPrecisionFiltersMissing() throws Exception {
    doThrow(new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)).when(items).page(any());
    mvc.perform(list(as(Role.SUPER_ADMIN, DataScope.ALL)).param("shopId", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"kolCode", "productCode", "weidianItemId"})
  void shouldReturn200_whenAnyPrecisionFilterAlone(String param) throws Exception {
    when(items.page(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    String value =
        switch (param) {
          case "kolCode" -> "KOL0001";
          case "productCode" -> "P000001";
          default -> "123456";
        };
    mvc.perform(list(as(Role.SUPER_ADMIN, DataScope.ALL)).param(param, value))
        .andExpect(status().isOk());
    verify(items).page(any());
  }

  @Test
  void shouldReturn400_whenNoPrecisionFiltersProvided() throws Exception {
    doThrow(new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED)).when(items).page(any());
    mvc.perform(list(as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }
}
