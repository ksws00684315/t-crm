package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.kol.api.CopyTargetShopApi.CopyChannelDTO;
import com.tcrm.admin.kol.api.CopyTargetShopApi.CopyShopDTO;
import com.tcrm.admin.product.service.ShopDTO;
import com.tcrm.admin.product.service.ShopOptionDTO;
import com.tcrm.admin.product.service.ShopService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
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
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldList_whenAnyRoleReads(Role role, DataScope scope) throws Exception {
    when(shops.page(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            new PageResult<>(
                List.of(
                    new ShopDTO(
                        1L,
                        "店",
                        "100",
                        1,
                        false,
                        null,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"))),
                1,
                1,
                20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].weidianShopId").value("100"));
  }

  @ParameterizedTest
  @CsvSource({"SUPER_ADMIN, ALL", "STAFF, ALL", "STAFF, OWN"})
  void shouldListOptions_whenAnyRoleReads(Role role, DataScope scope) throws Exception {
    when(shops.listOptions())
        .thenReturn(
            List.of(
                new ShopOptionDTO(2L, "S002", "乙店", "WEIDIAN", 1),
                new ShopOptionDTO(1L, "S001", "甲店", "WEIDIAN", 0)));
    mvc.perform(get(BASE + "/options").header(AdminMvcSupport.TOKEN_HEADER, as(role, scope)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].shopCode").value("S002"))
        .andExpect(jsonPath("$.data[0].shopName").value("乙店"))
        .andExpect(jsonPath("$.data[0].channel").value("WEIDIAN"))
        .andExpect(jsonPath("$.data[0].status").value(1))
        .andExpect(jsonPath("$.data[1].id").value(1));
    verify(shops).listOptions();
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
                false,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")));
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"shopName\":\"店\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\"100\",\"maxItems\":20000}"))
        .andExpect(status().isOk());
    verify(shops).create(any());
  }

  @ParameterizedTest
  @CsvSource({"STAFF, ALL", "STAFF, OWN"})
  void shouldReturn403_whenNonSuperAdminWrites(Role role, DataScope scope) throws Exception {
    String token = as(role, scope);
    mvc.perform(
            post(BASE)
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"shopName\":\"店\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\"100\",\"maxItems\":20000}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/1")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopName\":\"店\",\"channel\":\"WEIDIAN\",\"weidianShopId\":\"100\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(BASE + "/1/status")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(shops);
  }

  @Test
  void shouldListCopyChannelsWithDefaults() throws Exception {
    when(shops.copyChannels())
        .thenReturn(
            List.of(
                new CopyChannelDTO(
                    "WEIDIAN", 12, List.of(new CopyShopDTO(9L, "S009", "池店", 12)), 1, 1440)));
    mvc.perform(
            get(BASE + "/copy-channels")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].channel").value("WEIDIAN"))
        .andExpect(jsonPath("$.data[0].remainingCapacity").value(12))
        .andExpect(jsonPath("$.data[0].defaultQuota").value(1))
        .andExpect(jsonPath("$.data[0].defaultValidMinutes").value(1440))
        .andExpect(jsonPath("$.data[0].shops[0].shopCode").value("S009"));

    when(shops.copyChannels())
        .thenReturn(List.of(new CopyChannelDTO("WEIDIAN", 0, List.of(), 17, 240)));
    mvc.perform(
            get(BASE + "/copy-channels")
                .header(AdminMvcSupport.TOKEN_HEADER, as(Role.SUPER_ADMIN, DataScope.ALL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].defaultQuota").value(17))
        .andExpect(jsonPath("$.data[0].defaultValidMinutes").value(240));
    verify(shops, times(2)).copyChannels();
  }
}
