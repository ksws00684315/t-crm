package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tcrm.admin.shared.MailMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResendMailSenderTest {
  private final HttpServer server;
  private final String baseUrl;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final AtomicInteger failures = new AtomicInteger();

  ResendMailSenderTest() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(body);
          boolean fail = failures.getAndDecrement() > 0;
          byte[] payload =
              fail
                  ? "{\"message\":\"server error\"}".getBytes(StandardCharsets.UTF_8)
                  : "{}".getBytes();
          exchange.sendResponseHeaders(fail ? 500 : 200, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ResendMailSender sender() {
    return new ResendMailSender(
        "re_test_key", "T-CRM <no-reply@example.com>", new ObjectMapper(), baseUrl);
  }

  private static MailMessage mail() {
    return new MailMessage(
        "kol@example.com",
        "kol-invite",
        "[Acme] Set up your creator account / 设置您的达人账号",
        "text with https://kol.example.com/activate?token=raw",
        "<p>html</p>");
  }

  @Test
  void shouldPostJsonWithBearerKey_andSucceedOn200() {
    sender().send(mail());
    assertThat(authorizations).containsExactly("Bearer re_test_key");
    assertThat(bodies).hasSize(1);
    var body = new ObjectMapper().readTree(bodies.getFirst());
    assertThat(body.path("from").asString()).isEqualTo("T-CRM <no-reply@example.com>");
    assertThat(body.path("to").get(0).asString()).isEqualTo("kol@example.com");
    assertThat(body.path("subject").asString()).contains("Set up your creator account");
    assertThat(body.path("text").asString()).contains("activate?token=raw");
    assertThat(body.path("html").asString()).isEqualTo("<p>html</p>");
  }

  @Test
  void shouldRetryTwiceOnServerErrors_thenThrowWithoutContent() {
    failures.set(2); // first two attempts fail, third succeeds
    sender().send(mail());
    assertThat(bodies).hasSize(3);
  }

  @Test
  void shouldThrowAfterAllRetriesFailed() {
    failures.set(10);
    assertThatThrownBy(() -> sender().send(mail()))
        .isInstanceOf(ResendMailSender.MailSendException.class)
        .hasMessageContaining("500")
        .hasMessageNotContaining("raw");
    assertThat(bodies).hasSize(3);
  }
}
