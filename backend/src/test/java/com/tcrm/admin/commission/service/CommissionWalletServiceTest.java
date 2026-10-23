package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CommissionWalletServiceTest {
  @Test
  void shouldReadConfiguredMinimumAndAllowWithdrawal_whenBalanceMeetsThreshold() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemConfigApi configs = mock(SystemConfigApi.class);
    CommissionWalletService service =
        org.mockito.Mockito.spy(new CommissionWalletService(jdbc, mock(KolApi.class), configs));
    doReturn(new BigDecimal("250.00")).when(service).currentBalance(11L, 22L);
    org.mockito.Mockito.when(configs.getInt("commission.minimum-withdrawal-cny")).thenReturn(200);

    CommissionWalletService.Balance balance = service.withdrawalEligibility(11L, 22L);

    assertThat(balance.availableCny()).isEqualTo("250.00");
    assertThat(balance.minimumWithdrawalCny()).isEqualTo("200.00");
    assertThat(balance.withdrawalEligible()).isTrue();
    verify(configs).getInt("commission.minimum-withdrawal-cny");
  }

  @Test
  void shouldRejectWithdrawal_whenWalletHasUnrecoveredPrepayBalance() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemConfigApi configs = mock(SystemConfigApi.class);
    CommissionWalletService service =
        org.mockito.Mockito.spy(new CommissionWalletService(jdbc, mock(KolApi.class), configs));
    doReturn(new BigDecimal("-200.00")).when(service).currentBalance(11L, 22L);
    org.mockito.Mockito.when(configs.getInt("commission.minimum-withdrawal-cny")).thenReturn(200);

    CommissionWalletService.Balance balance = service.withdrawalEligibility(11L, 22L);

    assertThat(balance.availableCny()).isEqualTo("-200.00");
    assertThat(balance.withdrawalEligible()).isFalse();
  }
}
