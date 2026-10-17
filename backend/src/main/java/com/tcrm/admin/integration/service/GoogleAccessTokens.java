package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OAuth 2.0 access tokens for a service account (JWT bearer grant, RFC 7523), signed with the JDK
 * so that no Google client library is needed (ADR-021 §3). Tokens are cached per company in memory
 * and refreshed five minutes before they expire.
 */
@Component
public class GoogleAccessTokens {
  static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
  private static final Duration LIFETIME = Duration.ofHours(1);
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
  private static final Logger LOG = LoggerFactory.getLogger(GoogleAccessTokens.class);

  private final RestTemplate http;
  private final ObjectMapper json;
  private final String tokenUri;
  private final Clock clock;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  @Autowired
  public GoogleAccessTokens(
      RestTemplate googleRestTemplate,
      ObjectMapper json,
      @Value("${crm.google.token-uri}") String tokenUri) {
    this(googleRestTemplate, json, tokenUri, Clock.systemUTC());
  }

  GoogleAccessTokens(RestTemplate http, ObjectMapper json, String tokenUri, Clock clock) {
    this.http = http;
    this.json = json;
    this.tokenUri = tokenUri;
    this.clock = clock;
  }

  /** A valid token for the company's account, from cache or freshly issued. */
  String token(long tenantId, GoogleServiceAccount account) {
    Instant now = clock.instant();
    Cached cached = cache.get(tenantId);
    if (cached != null
        && cached.keyId().equals(account.privateKeyId())
        && cached.expiresAt().minus(REFRESH_MARGIN).isAfter(now)) {
      return cached.token();
    }
    Cached issued = issue(account, now);
    cache.put(tenantId, issued);
    return issued.token();
  }

  /** Drops a cached token, after the key changed or Google rejected the token. */
  void evict(long tenantId) {
    cache.remove(tenantId);
  }

  private Cached issue(GoogleServiceAccount account, Instant now) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
    form.add("assertion", assertion(account, now));
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    String body;
    try {
      body = http.postForObject(tokenUri, new HttpEntity<>(form, headers), String.class);
    } catch (HttpClientErrorException e) {
      // 400 invalid_grant / 401 invalid_client: deleted, disabled or wrong key.
      LOG.warn("Google token request rejected with HTTP {}", e.getStatusCode().value());
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    } catch (RestClientException e) {
      LOG.warn("Google token request failed: {}", e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.GOOGLE_API_FAILED);
    }
    JsonNode root = body == null ? null : json.readTree(body);
    JsonNode token = root == null ? null : root.get("access_token");
    JsonNode expiresIn = root == null ? null : root.get("expires_in");
    if (token == null || !token.isString() || token.asString().isBlank()) {
      throw new BusinessException(ErrorCode.GOOGLE_API_FAILED);
    }
    long seconds = expiresIn != null && expiresIn.canConvertToLong() ? expiresIn.asLong() : 3600;
    return new Cached(token.asString(), account.privateKeyId(), now.plusSeconds(seconds));
  }

  /** Signed JWT assertion: RS256, issuer = service account, audience = token endpoint. */
  String assertion(GoogleServiceAccount account, Instant now) {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    header.put("kid", account.privateKeyId());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", account.clientEmail());
    claims.put("scope", SCOPE);
    claims.put("aud", tokenUri);
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(LIFETIME).getEpochSecond());
    String unsigned =
        base64Url(json.writeValueAsBytes(header)) + "." + base64Url(json.writeValueAsBytes(claims));
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(account.privateKey());
      signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
      return unsigned + "." + base64Url(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    }
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record Cached(String token, String keyId, Instant expiresAt) {
    @Override
    public String toString() {
      return "Cached[token=redacted, expiresAt=%s]".formatted(expiresAt);
    }
  }
}
