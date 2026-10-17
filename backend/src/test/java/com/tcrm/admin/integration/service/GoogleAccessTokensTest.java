package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GoogleAccessTokensTest {
  private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
  private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private final RestTemplate http = new RestTemplate();
  private MockRestServiceServer server;
  private final MutableClock clock = new MutableClock(NOW);
  private GoogleAccessTokens tokens;
  private final GoogleServiceAccount account =
      new GoogleServiceAccount(
          GoogleTestKeys.EMAIL, GoogleTestKeys.KEY_ID, GoogleTestKeys.pair().getPrivate());

  @BeforeEach
  void setUp() {
    server = MockRestServiceServer.bindTo(http).build();
    tokens = new GoogleAccessTokens(http, json, TOKEN_URI, clock);
  }

  @Test
  void shouldSignAnRs256AssertionThatTheServiceAccountKeyVerifies() throws Exception {
    String jwt = tokens.assertion(account, NOW);
    String[] parts = jwt.split("\\.");

    JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
    JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertThat(header.get("alg").asString()).isEqualTo("RS256");
    assertThat(header.get("kid").asString()).isEqualTo(GoogleTestKeys.KEY_ID);
    assertThat(claims.get("iss").asString()).isEqualTo(GoogleTestKeys.EMAIL);
    assertThat(claims.get("aud").asString()).isEqualTo(TOKEN_URI);
    assertThat(claims.get("scope").asString()).isEqualTo(GoogleAccessTokens.SCOPE);
    assertThat(claims.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
    assertThat(claims.get("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 3600);
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(GoogleTestKeys.pair().getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  void shouldCacheTheTokenUntilFiveMinutesBeforeExpiry() {
    server
        .expect(ExpectedCount.times(2), requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"ya29.test\",\"expires_in\":3599,\"token_type\":\"Bearer\"}",
                MediaType.APPLICATION_JSON));

    assertThat(tokens.token(1L, account)).isEqualTo("ya29.test");
    clock.set(NOW.plusSeconds(3599 - 301));
    assertThat(tokens.token(1L, account)).isEqualTo("ya29.test");
    clock.set(NOW.plusSeconds(3599 - 299));
    assertThat(tokens.token(1L, account)).isEqualTo("ya29.test");

    server.verify();
  }

  @Test
  void shouldIssueAgain_whenEvicted() {
    server
        .expect(ExpectedCount.times(2), requestTo(TOKEN_URI))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"t\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

    tokens.token(1L, account);
    tokens.evict(1L);
    tokens.token(1L, account);

    server.verify();
  }

  @Test
  void shouldReportAnInvalidCredential_whenGoogleRejectsTheGrant() {
    server
        .expect(requestTo(TOKEN_URI))
        .andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}"));

    assertThatThrownBy(() -> tokens.token(1L, account))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
  }

  @Test
  void shouldReportAnApiFailure_whenGoogleFailsOrAnswersWithoutToken() {
    server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());
    server
        .expect(requestTo(TOKEN_URI))
        .andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> tokens.token(1L, account))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorCode())
          .isEqualTo(ErrorCode.GOOGLE_API_FAILED);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant value) {
      now = value;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
