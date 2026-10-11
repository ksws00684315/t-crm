package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WeidianClientTest {
  private final WeidianTokenCenter tokens = mock(WeidianTokenCenter.class);
  private final WeidianRateLimiter limiter = mock(WeidianRateLimiter.class);
  private final WeidianHttpGateway gateway = mock(WeidianHttpGateway.class);
  private final WeidianRetryDelay delay = mock(WeidianRetryDelay.class);
  private final WeidianClient client = new WeidianClient(tokens, limiter, gateway, delay);
  private final JsonMapper json = JsonMapper.builder().build();
  private final WeidianToken oldToken = new WeidianToken("app-key", "access-one");
  private final WeidianToken newToken = new WeidianToken("app-key", "access-two");

  @BeforeEach
  void returnOriginalTokenByDefault() {
    when(tokens.accessToken(3L)).thenReturn(oldToken);
  }

  @Test
  void shouldReturnResultAndRecordNoRetry_whenRequestSucceeds() {
    when(gateway.apiPost("vdian.item.get", "1.1", "access-one", "{}")).thenReturn(envelope(0));

    var result = client.call(3L, "vdian.item.get", "1.1", new Object());

    assertThat(result.path("value").intValue()).isEqualTo(9);
    verify(limiter).acquire("app-key");
    verify(delay, never()).pause(any());
  }

  @Test
  void shouldRetryTemporaryErrorsWithInjectedDelay() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}"))
        .thenReturn(envelope(10000))
        .thenReturn(envelope(0));

    client.call(3L, "m", "1.0", new Object());

    verify(delay).pause(Duration.ofSeconds(1));
  }

  @Test
  void shouldRefreshInvalidAccessTokenOnce_andRetryWithCurrentToken() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}")).thenReturn(envelope(10013));
    when(tokens.refreshAfterInvalid(3L, "access-one")).thenReturn(newToken);
    when(tokens.accessToken(3L)).thenReturn(oldToken, newToken);
    when(gateway.apiPost("m", "1.0", "access-two", "{}")).thenReturn(envelope(0));

    client.call(3L, "m", "1.0", new Object());

    verify(tokens).refreshAfterInvalid(3L, "access-one");
    verify(gateway).apiPost("m", "1.0", "access-two", "{}");
  }

  @Test
  void shouldMarkAuthorizationExpiredWithoutRetry_whenProviderRequiresReauthorization() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}")).thenReturn(envelope(10021));

    assertThatThrownBy(() -> client.call(3L, "m", "1.0", new Object()))
        .isInstanceOf(WeidianApiException.class)
        .hasMessage("Weidian authorization is expired");

    verify(tokens).markExpired(3L, "Weidian authorization expired, error 10021");
    verify(tokens, never()).refreshAfterInvalid(eq(3L), any());
    verify(delay, never()).pause(any());
  }

  @Test
  void shouldNotRetryPermissionErrors() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}")).thenReturn(envelope(10009));

    assertThatThrownBy(() -> client.call(3L, "m", "1.0", new Object()))
        .isInstanceOf(WeidianApiException.class)
        .hasMessage("Weidian API error 10009");

    verify(delay, never()).pause(any());
  }

  @Test
  void shouldFailAfterOnePassiveRefresh_whenTokenRemainsInvalid() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}")).thenReturn(envelope(10016));
    when(tokens.refreshAfterInvalid(3L, "access-one")).thenReturn(newToken);
    when(gateway.apiPost("m", "1.0", "access-two", "{}")).thenReturn(envelope(10013));

    assertThatThrownBy(() -> client.call(3L, "m", "1.0", new Object()))
        .isInstanceOf(WeidianApiException.class);

    verify(tokens).markExpired(3L, "Weidian access token stayed invalid after renewal");
  }

  private tools.jackson.databind.JsonNode envelope(int status) {
    return json.readTree(
        "{\"status\":{\"status_code\":%d,\"status_reason\":\"safe\"},\"result\":{\"value\":9}}"
            .formatted(status));
  }
}
