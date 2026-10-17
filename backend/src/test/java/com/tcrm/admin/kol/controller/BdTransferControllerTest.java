package com.tcrm.admin.kol.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.service.BdTransferDTO;
import com.tcrm.admin.kol.service.BdTransferExistsException;
import com.tcrm.admin.kol.service.BdTransferResultDTO;
import com.tcrm.admin.kol.service.BdTransferService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class BdTransferControllerTest {
  private static final String BASE = "/admin-api/kol/bd-transfers";
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final BdTransferService service = mock(BdTransferService.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new BdTransferController(service));

  private String admin() {
    return AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
  }

  @Test
  void shouldRejectAllEndpoints_whenStaffHasEveryPermission() throws Exception {
    String token =
        AdminMvcSupport.actAs(
            current, 2L, Role.STAFF, DataScope.ALL, Set.copyOf(Permission.codes()));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolIds\":[\"11\"]}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    mvc.perform(delete(BASE + "/21").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"kolIds\":[]}",
        "{\"kolIds\":[null]}",
        "{\"kolIds\":[\"0\"]}",
        "{\"effectiveAt\":\"bad\"}"
      })
  void shouldRejectInvalidBody_whenInputIsMalformed(String body) throws Exception {
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void shouldReturnCounts_whenCreated() throws Exception {
    when(service.create(any())).thenReturn(new BdTransferResultDTO(1, 0, 1));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolIds\":[\"11\",\"12\"],\"toBdUserId\":\"6\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.immediate").value(1))
        .andExpect(jsonPath("$.data.skipped").value(1));
  }

  @Test
  void shouldIncludeConflictingCodes_whenBatchHasReservations() throws Exception {
    when(service.create(any()))
        .thenThrow(new BdTransferExistsException(List.of("KOL0001", "KOL0002")));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kolIds\":[\"11\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("KOL_TRANSFER_EXISTS"))
        .andExpect(jsonPath("$.data[0]").value("KOL0001"))
        .andExpect(jsonPath("$.data[1]").value("KOL0002"));
  }

  @Test
  void shouldReturnStringIdsAndUtcTime_whenListing() throws Exception {
    when(service.page(any()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new BdTransferDTO(
                        9007199254740993L,
                        11L,
                        "KOL0001",
                        "Alice",
                        5L,
                        "Source",
                        null,
                        null,
                        Instant.parse("2026-11-01T00:00:00Z"),
                        BdTransferStatus.SCHEDULED,
                        null,
                        "handover",
                        null,
                        Instant.parse("2026-10-16T00:00:00Z"))),
                1,
                1,
                20));
    mvc.perform(
            get(BASE).header(AdminMvcSupport.TOKEN_HEADER, admin()).param("status", "SCHEDULED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].id").value("9007199254740993"))
        .andExpect(jsonPath("$.data.items[0].effectiveAt").value("2026-11-01T00:00:00Z"))
        .andExpect(jsonPath("$.data.items[0].kolCode").value("KOL0001"))
        .andExpect(jsonPath("$.data.items[0].kolName").value("Alice"))
        .andExpect(jsonPath("$.data.items[0].fromBdUserName").value("Source"))
        .andExpect(
            jsonPath("$.data.items[0].toBdUserName").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.items[0].reason").value("handover"));
  }

  @Test
  void shouldCancel_whenAdminRequestsDeletion() throws Exception {
    mvc.perform(delete(BASE + "/21").header(AdminMvcSupport.TOKEN_HEADER, admin()))
        .andExpect(status().isOk());
    verify(service).cancel(21L);
  }
}
