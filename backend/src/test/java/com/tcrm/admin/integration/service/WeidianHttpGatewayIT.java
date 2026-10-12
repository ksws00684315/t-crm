package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("docker")
class WeidianHttpGatewayIT {
  private HttpServer server;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private WeidianHttpGateway gateway;

  @BeforeEach
  void startLocalWeidianStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              "{\"status\":{\"status_code\":0},\"result\":{\"item_num\":1}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    gateway =
        new WeidianHttpGateway(
            new RestTemplate(),
            JsonMapper.builder().build(),
            "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
            "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stopLocalWeidianStub() {
    server.stop(0);
  }

  @Test
  void shouldCallLocalProtocolStubWithEncodedFormFields() {
    var response = gateway.apiPost("vdian.item.get", "1.1", "sample-token", "{\"q\":\"x&y\"}");

    assertThat(response.path("status").path("status_code").intValue()).isZero();
    assertThat(response.path("result").path("item_num").intValue()).isEqualTo(1);
    assertThat(requestBody.get()).contains("public=%7B", "param=%7B", "%26");
    assertThat(requestBody.get()).doesNotContain("q=x&y");
  }
}
