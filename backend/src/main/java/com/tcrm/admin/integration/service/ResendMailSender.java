package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.MailSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resend transport ({@code POST https://api.resend.com/emails}, design §7.3). Uses the JDK {@link
 * HttpClient} only — no new dependency. A request times out after 10 seconds; transport and server
 * failures retry twice with 1- and 3-second backoff, then throw {@link MailSendException} for the
 * caller to report.
 */
public class ResendMailSender implements MailSender {
  static final String DEFAULT_BASE_URL = "https://api.resend.com";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final long[] BACKOFF_SECONDS = {1, 3};

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json;
  private final String apiKey;
  private final Supplier<String> from;
  private final String baseUrl;

  public ResendMailSender(String apiKey, String from, ObjectMapper json) {
    this(apiKey, () -> from, json, DEFAULT_BASE_URL);
  }

  ResendMailSender(String apiKey, String from, ObjectMapper json, String baseUrl) {
    this(apiKey, () -> from, json, baseUrl);
  }

  public ResendMailSender(String apiKey, Supplier<String> from, ObjectMapper json) {
    this(apiKey, from, json, DEFAULT_BASE_URL);
  }

  ResendMailSender(String apiKey, Supplier<String> from, ObjectMapper json, String baseUrl) {
    this.apiKey = apiKey;
    this.from = from;
    this.json = json;
    this.baseUrl = baseUrl;
  }

  @Override
  public void send(MailMessage message) {
    ObjectNode body = json.createObjectNode();
    body.put("from", from.get());
    ArrayNode to = body.putArray("to");
    to.add(message.to());
    body.put("subject", message.subject());
    body.put("text", message.text());
    body.put("html", message.html());
    String payload = body.toString();
    IOException transportFailure = null;
    MailSendException statusFailure = null;
    for (int attempt = 0; attempt <= BACKOFF_SECONDS.length; attempt++) {
      if (attempt > 0) {
        try {
          Thread.sleep(BACKOFF_SECONDS[attempt - 1] * 1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/emails"))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      try {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
          return;
        }
        // The response body may echo parts of the message; log only the status.
        statusFailure = new MailSendException("Resend answered HTTP " + response.statusCode());
      } catch (IOException e) {
        transportFailure = e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MailSendException("Resend request interrupted", e);
      }
    }
    if (transportFailure != null) {
      throw new MailSendException("Resend request failed", transportFailure);
    }
    throw statusFailure == null
        ? new MailSendException("Resend request interrupted")
        : statusFailure;
  }

  /** Transport failure after all retries; carries no message content. */
  public static class MailSendException extends RuntimeException {
    MailSendException(String message) {
      super(message);
    }

    MailSendException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
