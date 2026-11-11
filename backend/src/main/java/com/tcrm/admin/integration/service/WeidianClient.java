package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.BusinessException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Central API caller for Weidian business requests. */
@Service
public class WeidianClient {
  private static final Logger LOG = LoggerFactory.getLogger(WeidianClient.class);
  private static final Duration[] BACKOFF = {
    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)
  };

  /** Request params are logged on failure so the call can be replayed; keep them bounded. */
  private static final int PARAM_LOG_LIMIT = 2000;

  private static final int REASON_LIMIT = 200;

  private final WeidianTokenCenter tokens;
  private final WeidianRateLimiter limiter;
  private final WeidianHttpGateway gateway;
  private final WeidianRetryDelay delay;
  private final JsonMapper requestJson = JsonMapper.builder().build();

  public WeidianClient(
      WeidianTokenCenter tokens,
      WeidianRateLimiter limiter,
      WeidianHttpGateway gateway,
      WeidianRetryDelay delay) {
    this.tokens = tokens;
    this.limiter = limiter;
    this.gateway = gateway;
    this.delay = delay;
  }

  /** Calls a documented method and returns its business {@code result} node. */
  public JsonNode call(long shopId, String method, String version, Object params) {
    String paramJson = stringify(params);
    long started = System.nanoTime();
    int retryCount = 0;
    int statusCode = -1;
    boolean passiveRefreshUsed = false;
    try {
      while (true) {
        WeidianToken token = tokens.accessToken(shopId);
        try {
          limiter.acquire(token.appKey());
          JsonNode envelope = gateway.apiPost(method, version, token.accessToken(), paramJson);
          statusCode = WeidianResponseParser.statusCode(envelope);
          if (statusCode == 0) {
            return WeidianResponseParser.resultOrThrow(envelope);
          }
          String reason = WeidianResponseParser.statusReason(envelope);
          // The numeric code alone says nothing actionable (task 134); Weidian's reason and the
          // request that produced it make the failure diagnosable and replayable. Params never
          // carry the access token (it travels in the public form field).
          LOG.warn(
              "Weidian API failure method={} version={} shopId={} statusCode={} reason={} params={}",
              method,
              version,
              shopId,
              statusCode,
              abbreviate(reason, REASON_LIMIT),
              abbreviate(paramJson, PARAM_LOG_LIMIT));
          if (statusCode == 10013 || statusCode == 10016) {
            if (passiveRefreshUsed) {
              throw new WeidianApiException(statusCode, "Weidian access token is invalid", false);
            }
            tokens.refreshAfterInvalid(shopId, token.accessToken());
            passiveRefreshUsed = true;
            retryCount++;
            continue;
          }
          if (statusCode == 10021 || statusCode == 10026) {
            tokens.markExpired(shopId, "Weidian authorization expired, error " + statusCode);
            throw new WeidianApiException(statusCode, "Weidian authorization is expired", false);
          }
          if (!WeidianResponseParser.isRetryable(statusCode) || retryCount >= BACKOFF.length) {
            throw new WeidianApiException(statusCode, failureMessage(statusCode, reason), false);
          }
          delay.pause(BACKOFF[retryCount]);
          retryCount++;
        } catch (WeidianTransportException e) {
          if (!e.retryable() || retryCount >= BACKOFF.length) {
            throw new WeidianApiException(
                WeidianApiException.NO_USABLE_RESPONSE, "Weidian API transport failed", false);
          }
          delay.pause(BACKOFF[retryCount]);
          retryCount++;
        }
      }
    } catch (BusinessException | WeidianApiException | WeidianTransportException e) {
      throw e;
    } finally {
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      LOG.info(
          "Weidian API method={} shopId={} statusCode={} durationMs={} retries={}",
          method,
          shopId,
          statusCode,
          elapsedMs,
          retryCount);
    }
  }

  /** Keeps Weidian's own explanation next to the numeric code (task 134). */
  static String failureMessage(int statusCode, String reason) {
    String base = "Weidian API error " + statusCode;
    String safe = abbreviate(reason, REASON_LIMIT);
    return safe.isEmpty() ? base : base + "：" + safe;
  }

  private static String abbreviate(String value, int limit) {
    if (value == null) {
      return "";
    }
    String flat = value.replaceAll("\\s+", " ").strip();
    return flat.length() > limit ? flat.substring(0, limit) + "…" : flat;
  }

  private String stringify(Object params) {
    try {
      return requestJson.writeValueAsString(params);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Unable to encode Weidian API parameters");
    }
  }
}
