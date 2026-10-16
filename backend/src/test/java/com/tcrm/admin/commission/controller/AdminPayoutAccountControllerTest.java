package com.tcrm.admin.commission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Controller slice for the admin payout-account endpoints (task 15a §4.4). */
class AdminPayoutAccountControllerTest {
  private static final String BASE = "/admin-api/commission/payout-accounts";

  private final PayoutAccountService accounts = mock(PayoutAccountService.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc =
      AdminMvcSupport.build(current, new AdminPayoutAccountController(accounts, current));

  private String reviewer() {
    return AdminMvcSupport.actAs(
        current, 5L, Role.STAFF, DataScope.OWN, Set.of(Permission.PAYOUT_ACCOUNT_REVIEW.code()));
  }

  private String viewer() {
    return AdminMvcSupport.actAs(
        current, 6L, Role.STAFF, DataScope.ALL, Set.of(Permission.COMMISSION_VIEW.code()));
  }

  private String noAccess() {
    return AdminMvcSupport.actAs(current, 7L, Role.STAFF, DataScope.ALL, Set.of("kol:view"));
  }

  private static PayoutAccountService.AdminAccountDTO row() {
    return new PayoutAccountService.AdminAccountDTO(
        77L,
        10L,
        "KOL0001",
        "Alice",
        PayoutAccountType.PAYPAL,
        "al***@example.com",
        "Alice Doe",
        null,
        null,
        PayoutAccountStatus.PENDING,
        false,
        null,
        Instant.parse("2026-10-17T00:00:00Z"),
        null);
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  void shouldListMaskedRows_forViewerOrReviewer() throws Exception {
    when(accounts.page(any(), any()))
        .thenReturn(new PageResult<>(java.util.List.of(row()), 1, 1, 20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, viewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value("1"))
        .andExpect(jsonPath("$.data.items[0].accountMasked").value("al***@example.com"))
        .andExpect(jsonPath("$.data.items[0].account").doesNotExist());
  }

  @Test
  void shouldDefaultToListPending() throws Exception {
    when(accounts.page(any(), any())).thenReturn(new PageResult<>(java.util.List.of(), 0, 1, 20));
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isOk());
    var filter = ArgumentCaptor.forClass(PayoutAccountService.Filter.class);
    verify(accounts).page(any(), filter.capture());
    org.assertj.core.api.Assertions.assertThat(filter.getValue().status()).isEqualTo("PENDING");
  }

  @Test
  void shouldAcceptAllAndFilters() throws Exception {
    when(accounts.page(any(), any())).thenReturn(new PageResult<>(java.util.List.of(), 0, 1, 20));
    mvc.perform(
            get(BASE)
                .param("status", "ALL")
                .param("type", "USDT")
                .param("kolCode", "KOL0001")
                .header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isOk());
    var filter = ArgumentCaptor.forClass(PayoutAccountService.Filter.class);
    verify(accounts).page(any(), filter.capture());
    org.assertj.core.api.Assertions.assertThat(filter.getValue().status()).isNull();
    org.assertj.core.api.Assertions.assertThat(filter.getValue().type()).isEqualTo("USDT");
  }

  @Test
  void shouldReturn400ForUnknownStatusOrType() throws Exception {
    mvc.perform(get(BASE).param("status", "NOPE").header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(BASE).param("type", "WECHAT").header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(accounts);
  }

  @Test
  void shouldCountPending_forBadge() throws Exception {
    when(accounts.pendingCount(any())).thenReturn(4L);
    mvc.perform(get(BASE + "/pending-count").header(AdminMvcSupport.TOKEN_HEADER, viewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.count").value("4"));
  }

  @Test
  void shouldShowFullAccountInDetail_forReviewerOnly() throws Exception {
    when(accounts.detail(any(), eq(77L)))
        .thenReturn(
            new PayoutAccountService.AdminAccountDetailDTO(
                77L,
                10L,
                "KOL0001",
                "Alice",
                PayoutAccountType.PAYPAL,
                "alice@example.com",
                "Alice Doe",
                null,
                null,
                PayoutAccountStatus.PENDING,
                false,
                null,
                Instant.parse("2026-10-17T00:00:00Z"),
                null));
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.account").value("alice@example.com"))
        .andExpect(jsonPath("$.data.accountMasked").doesNotExist());
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, viewer()))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE + "/77").header(AdminMvcSupport.TOKEN_HEADER, noAccess()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReviewWithReasonStampAndReject() throws Exception {
    String token = reviewer();
    String stamp = ",\"lastSubmittedAt\":\"2026-10-17T00:00:00Z\"";
    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"APPROVE\"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(accounts)
        .review(
            any(),
            eq(77L),
            eq(PayoutAccountService.ReviewAction.APPROVE),
            any(),
            eq(Instant.parse("2026-10-17T00:00:00Z")));

    // The submission stamp is required: the reviewer must name the version it saw.
    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"APPROVE\"}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());

    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"REJECT\"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    // Only the first (approve) call reached the service; the blank-reason reject was a 400.
    verify(accounts, org.mockito.Mockito.times(1)).review(any(), anyLong(), any(), any(), any());

    mvc.perform(
            json(
                    put(BASE + "/77/review"),
                    "{\"action\":\"REJECT\",\"reason\":\" blurry image \"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(accounts)
        .review(
            any(),
            eq(77L),
            eq(PayoutAccountService.ReviewAction.REJECT),
            eq("blurry image"),
            eq(Instant.parse("2026-10-17T00:00:00Z")));

    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"NOPE\"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"REJECT\"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, viewer()))
        .andExpect(status().isForbidden());
    mvc.perform(
            json(put(BASE + "/77/review"), "{\"action\":\"APPROVE\"" + stamp + "}")
                .header(AdminMvcSupport.TOKEN_HEADER, noAccess()))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldListForbiddenWithoutEitherPermission() throws Exception {
    mvc.perform(get(BASE).header(AdminMvcSupport.TOKEN_HEADER, noAccess()))
        .andExpect(status().isForbidden());
    mvc.perform(get(BASE + "/pending-count").header(AdminMvcSupport.TOKEN_HEADER, noAccess()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(accounts);
  }

  @Test
  void shouldDownloadFileOnlyWithReviewPermission() throws Exception {
    when(accounts.fileForAdmin(any(), eq(66L)))
        .thenReturn(
            new com.tcrm.admin.commission.service.BizFileService.FileDTO(
                66L, "qr.png", "image/png", 8L, new byte[] {1}));
    mvc.perform(get("/admin-api/files/66").header(AdminMvcSupport.TOKEN_HEADER, reviewer()))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getHeader("Content-Disposition"))
                    .contains("attachment"));
    mvc.perform(get("/admin-api/files/66").header(AdminMvcSupport.TOKEN_HEADER, viewer()))
        .andExpect(status().isForbidden());
    verify(accounts, org.mockito.Mockito.times(1)).fileForAdmin(any(), anyLong());
  }
}
