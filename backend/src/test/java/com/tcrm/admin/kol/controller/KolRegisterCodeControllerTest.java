package com.tcrm.admin.kol.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.service.KolRegisterCodeDTO;
import com.tcrm.admin.kol.service.KolRegisterCodeService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KolRegisterCodeControllerTest {
  private static final String CODE = "C2D3E4F5";

  private final KolRegisterCodeService codes = mock(KolRegisterCodeService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new KolRegisterCodeController(codes));

  @Test
  void shouldReturnTheLink_forABd() throws Exception {
    String token = AdminMvcSupport.actAs(current, 8L, Role.STAFF, DataScope.OWN);
    when(codes.current())
        .thenReturn(
            new KolRegisterCodeDTO(CODE, "https://kol.example.com/register?code=" + CODE, 1));
    mvc.perform(get("/admin-api/kol/register-code").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").value(CODE))
        .andExpect(jsonPath("$.data.url").value("https://kol.example.com/register?code=" + CODE))
        .andExpect(jsonPath("$.data.status").value(1));
  }

  @Test
  void shouldRotate_forABd() throws Exception {
    String token = AdminMvcSupport.actAs(current, 8L, Role.STAFF, DataScope.OWN);
    when(codes.rotate())
        .thenReturn(
            new KolRegisterCodeDTO(CODE, "https://kol.example.com/register?code=" + CODE, 1));
    mvc.perform(post("/admin-api/kol/register-code").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value(1));
  }

  @Test
  void shouldForbidAStaffAccountWithoutThePermission() throws Exception {
    String token = AdminMvcSupport.actAs(current, 5L, Role.STAFF, DataScope.ALL);
    mvc.perform(get("/admin-api/kol/register-code").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
    verifyNoInteractions(codes);
  }

  @Test
  void shouldMapAnUnusableBdTo422() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(codes.current()).thenThrow(new BusinessException(ErrorCode.KOL_INVALID_BD_USER));
    mvc.perform(get("/admin-api/kol/register-code").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.errorCode").value("KOL_INVALID_BD_USER"));
  }
}
