package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.kol.service.KolAuthService;
import com.tcrm.admin.kol.service.KolPortalProfileDTO;
import com.tcrm.admin.kol.service.KolPortalProfileService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.ClientIpResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class KolAuthControllerTest {
  private final KolAuthService auth = mock(KolAuthService.class);
  private final KolPortalProfileService profiles = mock(KolPortalProfileService.class);
  private final KolAccountStore accounts = mock(KolAccountStore.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final MockMvc mvc =
      KolMvcSupport.build(
          current,
          new KolAuthController(
              auth,
              profiles,
              accounts,
              new ClientIpResolver("", "CF-Connecting-IP,X-Forwarded-For")));

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        9007199254740993L,
        "KOL0001",
        "Alice",
        "alice@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        2L,
        "Acme",
        true,
        "+15550002222",
        "+15550001111",
        "https://docs.google.com/spreadsheets/d/abc");
  }

  private static KolAuthService.LoginResult login() {
    return new KolAuthService.LoginResult("X-Kol-Token", "the-token", 604800, kol());
  }

  private static KolPortalProfileDTO profile() {
    return new KolPortalProfileDTO(
        "KOL0001", "TikTok", "@alice", "APPROVED", "NORMAL", "PROMOTING", null, null);
  }

  @Test
  void shouldReturnLoginEnvelope_whenPasswordMatchesOneAccount() throws Exception {
    when(auth.login(anyString(), anyString(), anyString())).thenReturn(login());
    mvc.perform(
            post("/kol-api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"secret-pass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tokenName").value("X-Kol-Token"))
        .andExpect(jsonPath("$.data.token").value("the-token"))
        .andExpect(jsonPath("$.data.expiresIn").value(604800))
        .andExpect(jsonPath("$.data.kol.kolCode").value("KOL0001"))
        .andExpect(jsonPath("$.data.kol.name").value("Alice"))
        .andExpect(jsonPath("$.data.kol.email").value("alice@example.com"))
        .andExpect(jsonPath("$.data.kol.country").value("US"))
        .andExpect(jsonPath("$.data.kol.tenantName").value("Acme"))
        .andExpect(jsonPath("$.data.kol.passwordHash").doesNotExist());
  }

  @Test
  void shouldReturnCompanySelection_whenSeveralCompaniesMatch() throws Exception {
    when(auth.login(anyString(), anyString(), anyString()))
        .thenReturn(
            new KolAuthService.SelectionResult(
                "sel-token",
                List.of(
                    new KolAuthService.CompanyChoice(1L, "Acme"),
                    new KolAuthService.CompanyChoice(2L, "Beta"))));
    mvc.perform(
            post("/kol-api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\",\"password\":\"secret-pass\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.selectionToken").value("sel-token"))
        .andExpect(jsonPath("$.data.companies[0].tenantId").value("1"))
        .andExpect(jsonPath("$.data.companies[0].tenantName").value("Acme"))
        .andExpect(jsonPath("$.data.companies[1].tenantId").value("2"))
        .andExpect(jsonPath("$.data.companies[1].tenantName").value("Beta"))
        .andExpect(jsonPath("$.data.token").doesNotExist());
  }

  @Test
  void shouldReturn400_whenLoginBodyIsInvalid() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"email\":\"bad\",\"password\":\"x\"}",
            "{\"email\":\"a@x.io\"}",
            "{\"email\":\"a@x.io\",\"password\":\"\"}")) {
      mvc.perform(post("/kol-api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(auth);
  }

  @Test
  void shouldAnswerNullData_forSelectActivateForgotReset() throws Exception {
    when(auth.select(anyString(), anyLong())).thenReturn(login());
    mvc.perform(
            post("/kol-api/auth/login/select")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectionToken\":\"sel\",\"tenantId\":\"2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value("the-token"));
    when(auth.activate(anyString(), anyString(), anyString())).thenReturn(login());
    mvc.perform(
            post("/kol-api/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok\",\"password\":\"new-pass-123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value("the-token"));
    mvc.perform(
            post("/kol-api/auth/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.errorCode").doesNotExist());
    mvc.perform(
            post("/kol-api/auth/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tok\",\"password\":\"new-pass-123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void shouldRejectShortOrNonPrintablePasswords() throws Exception {
    for (String password : List.of("short", "uses \\u00e9 accent")) {
      mvc.perform(
              post("/kol-api/auth/activate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"token\":\"tok\",\"password\":\"" + password + "\"}"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(auth);
  }

  @Test
  void shouldRequireKolLogin_forMeLogoutAndPassword() throws Exception {
    mvc.perform(get("/kol-api/me")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/kol-api/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/kol-api/auth/logout")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/kol-api/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"old-pass-123\",\"newPassword\":\"new-pass-123\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(auth, current, profiles);
  }

  @Test
  void shouldServeMeAndLogout_whenKolTokenIsPresent() throws Exception {
    when(auth.me()).thenReturn(kol());
    when(profiles.get()).thenReturn(profile());
    String token = KolMvcSupport.actAs(current, kol());
    mvc.perform(get("/kol-api/me").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kolCode").value("KOL0001"))
        .andExpect(jsonPath("$.data.systemId").value("KOL0001"))
        .andExpect(jsonPath("$.data.tenantName").value("Acme"))
        .andExpect(jsonPath("$.data.phone").value("+15550002222"))
        .andExpect(jsonPath("$.data.whatsapp").value("+15550001111"))
        .andExpect(jsonPath("$.data.primaryPlatform").value("TikTok"))
        .andExpect(jsonPath("$.data.primaryHandle").value("@alice"))
        .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
        .andExpect(jsonPath("$.data.loginStatus").value("NORMAL"))
        .andExpect(jsonPath("$.data.relationStatus").value("PROMOTING"))
        .andExpect(jsonPath("$.data.commissionRate").doesNotExist())
        .andExpect(jsonPath("$.data.sheetUrl").doesNotExist());
    mvc.perform(post("/kol-api/auth/logout").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(auth).logout();
  }

  @Test
  void shouldUpdateOwnProfile_andAnswerWithTheFreshAccount() throws Exception {
    when(auth.me()).thenReturn(kol());
    when(profiles.get()).thenReturn(profile());
    when(accounts.getById(9007199254740993L))
        .thenReturn(
            new KolAccountDTO(
                9007199254740993L,
                "KOL0001",
                "Alice Kim",
                "alice@example.com",
                "US",
                "hash",
                1,
                1,
                0,
                Instant.EPOCH,
                2L,
                "Acme",
                true,
                "+15550002222",
                "+15550001111",
                "https://docs.google.com/spreadsheets/d/abc"));
    String token = KolMvcSupport.actAs(current, kol());
    mvc.perform(
            put("/kol-api/me")
                .header(KolMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Alice Kim\",\"primaryPlatform\":\"Instagram\","
                        + "\"primaryHandle\":\"@alice.kim\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Alice Kim"))
        .andExpect(jsonPath("$.data.primaryPlatform").value("TikTok"));
    verify(profiles).update("Alice Kim", "Instagram", "@alice.kim", "127.0.0.1");
  }

  @Test
  void shouldRejectInvalidProfileBodies_atBinding() throws Exception {
    String token = KolMvcSupport.actAs(current, kol());
    for (String body : List.of("{}", "{\"name\":\"  \"}")) {
      mvc.perform(
              put("/kol-api/me")
                  .header(KolMvcSupport.TOKEN_HEADER, token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(profiles);
  }

  @Test
  void shouldRejectNonKolTokens() throws Exception {
    // An administrator token is a session in another namespace: the KOL interceptor must not
    // authenticate it even when it travels in the X-Kol-Token header.
    String adminToken =
        com.tcrm.admin.KolMvcSupport.actAs(current, kol()).substring(0, 8) + "-not-a-kol-token";
    mvc.perform(get("/kol-api/me").header(KolMvcSupport.TOKEN_HEADER, adminToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturnFreshLogin_whenPasswordChanges() throws Exception {
    when(auth.changePassword(anyString(), anyString(), anyString())).thenReturn(login());
    String token = KolMvcSupport.actAs(current, kol());
    mvc.perform(
            put("/kol-api/me/password")
                .header(KolMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"old-pass-123\",\"newPassword\":\"new-pass-123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value("the-token"))
        .andExpect(jsonPath("$.data.kol.kolCode").value("KOL0001"));
  }
}
