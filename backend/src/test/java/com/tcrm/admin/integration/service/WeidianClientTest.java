package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

  private final Logger clientLogger = (Logger) LoggerFactory.getLogger(WeidianClient.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void returnOriginalTokenByDefault() {
    when(tokens.accessToken(3L)).thenReturn(oldToken);
    logs.start();
    clientLogger.addAppender(logs);
  }

  @AfterEach
  void detachLogs() {
    clientLogger.detachAppender(logs);
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
        .hasMessage("Weidian API error 10009：safe");

    verify(delay, never()).pause(any());
  }

  @Test
  void shouldCarryWeidianReasonIntoErrorMessage() {
    when(gateway.apiPost("vdian.item.add", "1.5", "access-one", "{}"))
        .thenReturn(
            json.readTree(
                "{\"status\":{\"status_code\":200056,\"status_reason\":\"商品数量已达上限\"},"
                    + "\"result\":null}"));

    assertThatThrownBy(() -> client.call(3L, "vdian.item.add", "1.5", new Object()))
        .isInstanceOf(WeidianApiException.class)
        .hasMessage("Weidian API error 200056：商品数量已达上限");
  }

  @Test
  void shouldKeepBareCode_whenReasonIsBlank() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}"))
        .thenReturn(
            json.readTree(
                "{\"status\":{\"status_code\":200056,\"status_reason\":\"  \"},\"result\":null}"));

    assertThatThrownBy(() -> client.call(3L, "m", "1.0", new Object()))
        .isInstanceOf(WeidianApiException.class)
        .hasMessage("Weidian API error 200056");
  }

  @Test
  void shouldLogFailedRequest_whenWeidianRejects() {
    when(gateway.apiPost("vdian.item.add", "1.5", "access-one", "{}"))
        .thenReturn(
            json.readTree(
                "{\"status\":{\"status_code\":200056,\"status_reason\":\"商品数量已达上限\"},"
                    + "\"result\":null}"));

    assertThatThrownBy(() -> client.call(3L, "vdian.item.add", "1.5", new Object()))
        .isInstanceOf(WeidianApiException.class);

    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("method=vdian.item.add")
                  .contains("version=1.5")
                  .contains("shopId=3")
                  .contains("statusCode=200056")
                  .contains("reason=商品数量已达上限")
                  .contains("params={}");
            });
    assertThat(logs.list)
        .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("access-one"));
  }

  @Test
  void shouldFailAfterOnePassiveRefreshWithoutMarkingShopExpired_whenTokenRemainsInvalid() {
    when(gateway.apiPost("m", "1.0", "access-one", "{}")).thenReturn(envelope(10016));
    when(tokens.refreshAfterInvalid(3L, "access-one")).thenReturn(newToken);
    when(gateway.apiPost("m", "1.0", "access-two", "{}")).thenReturn(envelope(10013));

    assertThatThrownBy(() -> client.call(3L, "m", "1.0", new Object()))
        .isInstanceOf(WeidianApiException.class);

    verify(tokens, never()).markExpired(eq(3L), any());
  }

  private tools.jackson.databind.JsonNode envelope(int status) {
    return json.readTree(
        "{\"status\":{\"status_code\":%d,\"status_reason\":\"safe\"},\"result\":{\"value\":9}}"
            .formatted(status));
  }
}
