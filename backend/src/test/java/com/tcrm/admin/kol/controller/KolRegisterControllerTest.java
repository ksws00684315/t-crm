package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.kol.service.KolAuthService;
import com.tcrm.admin.kol.service.KolRegisterPreview;
import com.tcrm.admin.kol.service.KolRegistration;
import com.tcrm.admin.kol.service.KolSelfRegisterService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.ClientIpResolver;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class KolRegisterControllerTest {
  private static final String CODE = "D2E3F4G5";

  private final KolSelfRegisterService registration = mock(KolSelfRegisterService.class);
  private final MockMvc mvc =
      KolMvcSupport.build(
          mock(CurrentKolContext.class),
          new KolRegisterController(
              registration, new ClientIpResolver("", "CF-Connecting-IP,X-Forwarded-For")));

  @Test
  void shouldPreviewWithoutAToken() throws Exception {
    when(registration.preview(
            org.mockito.ArgumentMatchers.eq(CODE), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(new KolRegisterPreview("Acme BD", "Acme"));
    mvc.perform(get("/kol-api/register/" + CODE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bdUserName").value("Acme BD"))
        .andExpect(jsonPath("$.data.tenantName").value("Acme"))
        .andExpect(jsonPath("$.data.code").doesNotExist());
  }

  @Test
  void shouldRejectAMalformedRegistrationBody() throws Exception {
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"short\",\"email\":\"a@b.co\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  @Test
  void shouldReturnTheSameEnvelopeAsLogin() throws Exception {
    when(registration.register(any(), anyString()))
        .thenReturn(
            new KolAuthService.LoginResult(
                "X-Kol-Token",
                "the-token",
                604800,
                new KolAccountDTO(
                    42L,
                    "KOL0001",
                    "Ada",
                    "ada@example.com",
                    "US",
                    "hash",
                    1,
                    1,
                    0,
                    Instant.EPOCH,
                    2L,
                    "Acme",
                    true)));
    mvc.perform(
            post("/kol-api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\""
                        + CODE
                        + "\",\"email\":\"ada@example.com\","
                        + "\"password\":\"register-pass-1\",\"name\":\"Ada\",\"whatsapp\":\"+15551234567\","
                        + "\"primaryPlatform\":\"TikTok\",\"primaryHandle\":\"ada\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tokenName").value("X-Kol-Token"))
        .andExpect(jsonPath("$.data.token").value("the-token"))
        .andExpect(jsonPath("$.data.kol.kolCode").value("KOL0001"))
        .andExpect(jsonPath("$.data.kol.passwordHash").doesNotExist());
    verify(registration).register(any(KolRegistration.class), anyString());
  }
}
