package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.WithdrawalDetailMapper;
import com.tcrm.admin.commission.model.WithdrawalDetailRow;
import com.tcrm.admin.commission.model.WithdrawalOrderRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.SecretCipher;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WithdrawalDetailServiceTest {
  private final WithdrawalDetailMapper mapper = mock(WithdrawalDetailMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final SecretCipher cipher = mock(SecretCipher.class);
  private final AccountDTO user = mock(AccountDTO.class);
  private final WithdrawalDetailService service =
      new WithdrawalDetailService(mapper, kols, new ObjectMapper(), cipher);

  private void authorized() {
    when(user.tenantId()).thenReturn(11L);
    when(user.role()).thenReturn(Role.STAFF);
    when(user.permissions()).thenReturn(Set.of("commission:view"));
  }

  @Test
  void deniesPlatformAndMissingPermissionBeforeReading() {
    when(user.role()).thenReturn(Role.PLATFORM_ADMIN);
    assertThatThrownBy(() -> service.detail(user, 1)).isInstanceOf(BusinessException.class);
    when(user.role()).thenReturn(Role.STAFF);
    when(user.tenantId()).thenReturn(11L);
    when(user.permissions()).thenReturn(Set.of("prepay:review"));
    assertThatThrownBy(() -> service.detail(user, 1)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(mapper);
  }

  @Test
  void refusesCrossTenantAndOtherBd() {
    authorized();
    when(mapper.find(11, 1)).thenReturn(new WithdrawalDetailRow(1L, 22L, 3L, "{}", "cipher"));
    assertThatThrownBy(() -> service.orders(user, 1, 1, 10)).isInstanceOf(BusinessException.class);
    when(mapper.find(11, 1)).thenReturn(new WithdrawalDetailRow(1L, 11L, 3L, "{}", "cipher"));
    when(user.ownScope()).thenReturn(true);
    when(user.id()).thenReturn(4L);
    when(kols.listByIdsIncludingDeleted(List.of(3L)))
        .thenReturn(List.of(new KolRefDTO(3L, "K3", "Creator", true, 5L)));
    assertThatThrownBy(() -> service.orders(user, 1, 1, 10)).isInstanceOf(BusinessException.class);
  }

  @Test
  void pagesDistinctOrdersAndPreservesUnknownHistoricalBalance() {
    authorized();
    when(mapper.find(11, 1)).thenReturn(new WithdrawalDetailRow(1L, 11L, 3L, "{}", "cipher"));
    when(mapper.countOrders(11, 1)).thenReturn(11L);
    when(mapper.orders(11, 1, 10, 10))
        .thenReturn(
            List.of(
                new WithdrawalOrderRow(
                    21L,
                    "ORDER11",
                    "[{\"id\":\"p1\",\"product\":\"Long name\",\"sku\":\"L\"},{\"id\":\"p2\",\"product\":\"Second\",\"sku\":\"M\"}]",
                    new BigDecimal("100.00"),
                    null)));
    var page = service.orders(user, 1, 2, 10);
    assertThat(page.total()).isEqualTo(11);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().products()).hasSize(2);
    assertThat(page.items().getFirst().occupied()).isEqualTo("100.00");
    assertThat(page.items().getFirst().remainingSnapshot()).isNull();
  }

  @Test
  void returnsExplicitStageNotesAndEncryptedRecipientWithoutInventingHistory() {
    authorized();
    when(user.role()).thenReturn(Role.SUPER_ADMIN);
    when(mapper.find(11, 1))
        .thenReturn(
            new WithdrawalDetailRow(
                1L,
                11L,
                3L,
                "{\"paused\":false,\"id\":\"untrusted\",\"kolId\":\"untrusted\",\"operationsNote\":\"Ops approved\",\"financeNote\":\"Paid\",\"amount\":\"200.00\"}",
                "cipher"));
    when(cipher.decrypt("cipher"))
        .thenReturn(
            "{\"type\":\"PAYPAL\",\"account\":\"snapshot@example.test\",\"currency\":\"USD\"}");
    var detail = service.detail(user, 1);
    assertThat(detail.id()).isEqualTo("1");
    assertThat(detail.kolId()).isEqualTo("3");
    assertThat(detail.operationsNote()).isEqualTo("Ops approved");
    assertThat(detail.financeNote()).isEqualTo("Paid");
    assertThat(detail.account().account()).isEqualTo("snapshot@example.test");
    assertThat(detail.history()).isEmpty();
    assertThat(detail.allocations()).isEmpty();
  }

  @Test
  void ordinaryViewerGetsMaskedRecipientAndNoFinancialRemarksEvenWithPrepayPermission() {
    authorized();
    when(user.permissions()).thenReturn(Set.of("commission:view", "prepay:pay"));
    when(mapper.find(11, 1))
        .thenReturn(
            new WithdrawalDetailRow(
                1L,
                11L,
                3L,
                "{\"paused\":false,\"reviewNote\":\"Private last review\",\"operationsNote\":\"Ops\",\"financeNote\":\"Private\",\"history\":[{\"action\":\"PAID\",\"note\":\"Private paid\"},{\"action\":\"PENDING_FINANCE_REVIEW\",\"note\":\"Public ops\"},{\"action\":\"PENDING_PAYMENT\",\"note\":\"Private finance\"}],\"payment\":{\"financeNote\":\"Private pay\"}}",
                "cipher"));
    when(cipher.decrypt("cipher"))
        .thenReturn("{\"account\":\"snapshot@example.test\",\"holder\":\"Creator Name\"}");
    var detail = service.detail(user, 1);
    assertThat(detail.account().account()).isEqualTo("sn***@example.test");
    assertThat(detail.account().holder()).isNotEqualTo("Creator Name");
    assertThat(detail.financeNote()).isNull();
    assertThat(detail.reviewNote()).isNull();
    assertThat(detail.payment().financeNote()).isNull();
    assertThat(detail.history().getFirst().note()).isNull();
    assertThat(detail.operationsNote()).isEqualTo("Ops");
    assertThat(detail.history().get(1).note()).isEqualTo("Public ops");
    assertThat(detail.history().get(2).note()).isNull();
    when(user.postTemplate()).thenReturn(com.tcrm.admin.security.api.PostTemplate.FINANCE);
    var finance = service.detail(user, 1);
    assertThat(finance.financeNote()).isEqualTo("Private");
    assertThat(finance.payment().financeNote()).isEqualTo("Private pay");
    assertThat(finance.history().getFirst().note()).isEqualTo("Private paid");
    assertThat(finance.account().account()).isEqualTo("sn***@example.test");
  }
}
