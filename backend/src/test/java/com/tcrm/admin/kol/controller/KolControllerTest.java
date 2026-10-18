package com.tcrm.admin.kol.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.tcrm.admin.kol.service.BdHistoryDTO;
import com.tcrm.admin.kol.service.CreateKolCommand;
import com.tcrm.admin.kol.service.EditKolCommand;
import com.tcrm.admin.kol.service.KolClaimService;
import com.tcrm.admin.kol.service.KolDTO;
import com.tcrm.admin.kol.service.KolDetailDTO;
import com.tcrm.admin.kol.service.KolFilterDTO;
import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolService;
import com.tcrm.admin.kol.service.SocialAccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class KolControllerTest {
  private static final String BASE = "/admin-api/kol/kols";
  private static final String VALID =
      """
      {"name":"Alice","email":"alice@example.com","country":"US",
       "socialAccounts":[{"platform":"TikTok","handle":"@a","url":"https://t.example/a",
       "followers":"12000"}],"bdUserId":"5","defaultRate":"0.155","remark":"VIP"}
      """;

  private final KolService kols = mock(KolService.class);
  private final KolPromotionService promotions = mock(KolPromotionService.class);
  private final KolClaimService claims = mock(KolClaimService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new KolController(kols, promotions, claims));

  private static KolDTO kol() {
    return new KolDTO(
        9007199254740993L,
        "KOL-AAA001",
        "Alice",
        "alice@example.com",
        "US",
        List.of(new SocialAccountDTO("TikTok", "@a", null, 9007199254740993L, false)),
        5L,
        "bd5",
        new BigDecimal("0.155"),
        1,
        null,
        "+12345678",
        77L,
        "kakobuy",
        "aff-01",
        "https://docs.google.com/spreadsheets/d/1/edit",
        "PROMOTING",
        "PROMOTING",
        "https://landing.example/alice",
        List.of(77L),
        1,
        0,
        Instant.parse("2026-10-05T00:00:00Z"),
        Instant.parse("2026-10-05T00:00:01Z"));
  }

  private String as(Role role, DataScope scope) {
    return AdminMvcSupport.actAs(current, 1L, role, scope);
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldListWithStringIdsAndFourDecimalRate_whenAnyRoleReads(Role role, DataScope scope)
      throws Exception {
    when(kols.page(any())).thenReturn(new PageResult<>(List.of(kol()), 1, 1, 20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value("9007199254740993"))
        .andExpect(jsonPath("$.data.items[0].bdUserId").value("5"))
        .andExpect(jsonPath("$.data.items[0].bdUserName").value("bd5"))
        .andExpect(jsonPath("$.data.items[0].defaultRate").value("0.1550"))
        .andExpect(
            jsonPath("$.data.items[0].socialAccounts[0].followers").value("9007199254740993"))
        .andExpect(jsonPath("$.data.items[0].accountStatus").value(0))
        .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-05T00:00:00Z"));
  }

  @Test
  void shouldPassFiltersAndDefaults_whenListing() throws Exception {
    when(kols.page(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    mvc.perform(
            get(BASE)
                .param("keyword", "ali")
                .param("country", "us")
                .param("status", "1")
                .param("bdUserId", "5")
                .param("displayStatus", "PENDING_UPDATE")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.OWN)))
        .andExpect(status().isOk());
    verify(kols).page(new KolFilterDTO("ali", "us", 1, 5L, "PENDING_UPDATE", 1, 20));
  }

  @ParameterizedTest
  @CsvSource({
    "size, 101",
    "page, 0",
    "status, 2",
    "country, USA",
    "bdUserId, 0",
    "displayStatus, FOO"
  })
  void shouldReturn400_whenListParameterIsInvalid(String name, String value) throws Exception {
    mvc.perform(
            get(BASE)
                .param(name, value)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(kols);
  }

  @Test
  void shouldReturnFlatDetailWithHistory() throws Exception {
    when(kols.get(11L))
        .thenReturn(
            new KolDetailDTO(
                kol(),
                List.of(
                    new BdHistoryDTO(5L, "bd5", Instant.parse("2026-10-02T00:00:00Z"), null),
                    new BdHistoryDTO(
                        null,
                        null,
                        Instant.parse("2026-10-01T00:00:00Z"),
                        Instant.parse("2026-10-02T00:00:00Z")))));
    mvc.perform(
            get(BASE + "/11").header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kolCode").value("KOL-AAA001"))
        .andExpect(jsonPath("$.data.accountStatus").value(0))
        .andExpect(jsonPath("$.data.kol").doesNotExist())
        .andExpect(jsonPath("$.data.bdHistory[0].bdUserName").value("bd5"))
        .andExpect(jsonPath("$.data.bdHistory[0].endTime").doesNotExist())
        .andExpect(jsonPath("$.data.bdHistory[1].bdUserId").doesNotExist())
        .andExpect(jsonPath("$.data.bdHistory[1].endTime").value("2026-10-02T00:00:00Z"));
  }

  @Test
  void shouldReturn403_whenServiceDeniesBdAccessToAnotherKol() throws Exception {
    when(kols.get(11L)).thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN));
    mvc.perform(
            get(BASE + "/11").header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.OWN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_FORBIDDEN"));
  }

  @Test
  void shouldCreateAndConvertRequest_whenOperatorPosts() throws Exception {
    when(kols.create(any())).thenReturn(kol());
    mvc.perform(
            json(post(BASE), VALID)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.STAFF, DataScope.ALL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kolCode").value("KOL-AAA001"))
        .andExpect(jsonPath("$.data.whatsapp").value("+12345678"))
        .andExpect(jsonPath("$.data.linkTemplateId").value("77"))
        .andExpect(jsonPath("$.data.linkTemplateName").value("kakobuy"))
        .andExpect(jsonPath("$.data.inviteCode").value("aff-01"))
        .andExpect(jsonPath("$.data.registerSource").value(1))
        .andExpect(jsonPath("$.data.accountStatus").value(0));
    var command = ArgumentCaptor.forClass(CreateKolCommand.class);
    verify(kols).create(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue())
        .isEqualTo(
            new CreateKolCommand(
                "Alice",
                "alice@example.com",
                "US",
                List.of(new SocialAccountDTO("TikTok", "@a", "https://t.example/a", 12000L, false)),
                5L,
                new BigDecimal("0.155"),
                "VIP",
                null,
                null,
                null,
                null));
  }

  @Test
  void shouldConvertProfileFields_whenCreateCarriesThem() throws Exception {
    when(kols.create(any())).thenReturn(kol());
    mvc.perform(
            json(
                    post(BASE),
                    "{\"name\":\"A\",\"email\":\"a@x.io\",\"whatsapp\":\"+12345678\","
                        + "\"linkTemplateId\":\"77\",\"inviteCode\":\"aff-01\","
                        + "\"sheetUrl\":\"https://docs.google.com/spreadsheets/d/1\","
                        + "\"socialAccounts\":[{\"platform\":\"T\",\"handle\":\"h\",\"primary\":true}]}")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isOk());
    var command = ArgumentCaptor.forClass(CreateKolCommand.class);
    verify(kols).create(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue())
        .isEqualTo(
            new CreateKolCommand(
                "A",
                "a@x.io",
                null,
                List.of(new SocialAccountDTO("T", "h", null, null, true)),
                null,
                null,
                null,
                "+12345678",
                77L,
                "aff-01",
                "https://docs.google.com/spreadsheets/d/1"));
  }

  @Test
  void shouldReturn400_whenTwoSocialAccountsClaimPrimary() throws Exception {
    when(kols.create(any())).thenReturn(kol());
    mvc.perform(
            json(
                    post(BASE),
                    "{\"name\":\"A\",\"email\":\"a@x.io\","
                        + "\"socialAccounts\":[{\"platform\":\"T\",\"handle\":\"h\",\"primary\":true},"
                        + "{\"platform\":\"I\",\"handle\":\"i\",\"primary\":true}]}")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("KOL_MULTIPLE_PRIMARY"));
    verifyNoInteractions(kols);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"name\":\"\",\"email\":\"a@x.io\",\"country\":\"US\"}",
        "{\"name\":\"A\",\"email\":\"bad\",\"country\":\"US\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"XX\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\",\"defaultRate\":\"1.0001\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\",\"defaultRate\":\"-0.1\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\",\"defaultRate\":\"0.12345\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\",\"bdUserId\":\"0\"}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\","
            + "\"socialAccounts\":[{\"platform\":\"T\",\"handle\":\"h\",\"url\":\"http://x.io\"}]}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\","
            + "\"socialAccounts\":[{\"platform\":\"\",\"handle\":\"h\"}]}",
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\","
            + "\"socialAccounts\":[{\"platform\":\"T\",\"handle\":\"h\",\"followers\":-1}]}"
      })
  void shouldReturn400_whenCreateBodyIsInvalid(String body) throws Exception {
    mvc.perform(
            json(post(BASE), body)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
    verifyNoInteractions(kols);
  }

  @Test
  void shouldReportBodyFieldNames_whenEditBodyIsInvalid() throws Exception {
    // The constrained path variable (@Min id) switches Spring to method validation; the body's
    // field errors must still be reported by field, not as the parameter name "r".
    mvc.perform(
            json(put(BASE + "/11"), "{\"name\":\"\",\"email\":\"bad\",\"country\":\"US\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.data[*].field").value(containsInAnyOrder("email", "name")));
    verifyNoInteractions(kols);
  }

  @Test
  void shouldReportPathParameterName_whenIdIsBelowMinimum() throws Exception {
    mvc.perform(
            json(put(BASE + "/0"), VALID)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.data[0].field").value("id"));
    verifyNoInteractions(kols);
  }

  @Test
  void shouldReturn400_whenMoreThanTenSocialAccounts() throws Exception {
    String account = "{\"platform\":\"T\",\"handle\":\"h\"}";
    String body =
        "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"US\",\"socialAccounts\":["
            + String.join(",", java.util.Collections.nCopies(11, account))
            + "]}";
    mvc.perform(
            json(post(BASE), body)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(kols);
  }

  @Test
  void shouldMapServiceErrors_whenCreateConflictsOrBdIsInvalid() throws Exception {
    when(kols.create(any()))
        .thenThrow(new BusinessException(ErrorCode.KOL_EMAIL_EXISTS))
        .thenThrow(new BusinessException(ErrorCode.KOL_INVALID_BD_USER));
    String token = as(Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(json(post(BASE), VALID).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_EMAIL_EXISTS"));
    mvc.perform(json(post(BASE), VALID).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.errorCode").value("KOL_INVALID_BD_USER"));
  }

  @Test
  void shouldEditStatusAndBd_whenSuperAdminWrites() throws Exception {
    String token = as(Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            json(
                    put(BASE + "/11"),
                    "{\"name\":\"A\",\"email\":\"a@x.io\",\"country\":\"gb\",\"defaultRate\":0}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(kols)
        .update(
            11L,
            new EditKolCommand(
                "A", "a@x.io", "gb", List.of(), new BigDecimal("0"), null, null, null, null, null));
    mvc.perform(
            json(put(BASE + "/11/status"), "{\"status\":0}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(kols).updateStatus(11L, 0);
    mvc.perform(
            json(put(BASE + "/11/bd-user"), "{\"bdUserId\":null}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(kols).updateBdUser(eq(11L), isNull());
    mvc.perform(
            json(put(BASE + "/11/bd-user"), "{\"bdUserId\":\"6\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(kols).updateBdUser(11L, 6L);
  }

  @Test
  void shouldReturn400_whenStatusIsMissingOrOutOfRange() throws Exception {
    String token = as(Role.SUPER_ADMIN, DataScope.ALL);
    for (String body : List.of("{}", "{\"status\":2}")) {
      mvc.perform(json(put(BASE + "/11/status"), body).header(AdminMvcSupport.TOKEN_HEADER, token))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(kols);
  }

  @Test
  void shouldReturn403WithoutCallingService_whenStaffLacksKolEdit() throws Exception {
    String token =
        AdminMvcSupport.actAs(current, 1L, Role.STAFF, DataScope.ALL, java.util.Set.of("kol:view"));
    mvc.perform(json(post(BASE), VALID).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(json(put(BASE + "/11"), VALID).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(put(BASE + "/11/status"), "{\"status\":0}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(put(BASE + "/11/bd-user"), "{\"bdUserId\":null}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(post(BASE + "/11/invite").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(kols);
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldResendInvite_whenWriterCalls(Role role, DataScope scope) throws Exception {
    String token = as(role, scope);
    mvc.perform(post(BASE + "/11/invite").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(kols).resendInvite(11L);
  }

  @Test
  void shouldMapConflict_whenKolIsAlreadyActivated() throws Exception {
    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.KOL_ALREADY_ACTIVATED))
        .when(kols)
        .resendInvite(anyLong());
    mvc.perform(
            post(BASE + "/11/invite")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_ALREADY_ACTIVATED"));
  }

  @Test
  void shouldReturn403_whenServiceRejectsBdChangeByBd() throws Exception {
    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN))
        .when(kols)
        .updateBdUser(anyLong(), any());
    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN))
        .when(kols)
        .updateStatus(anyLong(), anyInt());
    String token = as(Role.STAFF, DataScope.OWN);
    mvc.perform(
            json(put(BASE + "/11/bd-user"), "{\"bdUserId\":\"5\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(put(BASE + "/11/status"), "{\"status\":1}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn401_whenNotLoggedIn() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    verifyNoInteractions(kols);
  }
}
