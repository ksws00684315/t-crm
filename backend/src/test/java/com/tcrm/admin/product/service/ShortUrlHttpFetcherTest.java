package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Header-only parsing against a local stub server; no external network (task 57). */
class ShortUrlHttpFetcherTest {
  private final ShortUrlHttpFetcher fetcher = new ShortUrlHttpFetcher();
  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<String> lastTarget = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/redir",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "https://weidian.com/item.html?itemID=123");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/plain",
        exchange -> {
          lastMethod.set(exchange.getRequestMethod());
          lastTarget.set(exchange.getRequestURI().toString());
          byte[] body = "would-be-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("X-Probe", "yes");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/method",
        exchange -> {
          lastMethod.set(exchange.getRequestMethod());
          exchange.sendResponseHeaders(405, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private URI uri(String path) {
    return URI.create(
        "http://" + server.getAddress().getHostName() + ":" + server.getAddress().getPort() + path);
  }

  @Test
  void shouldParseStatusAndHeaders_fromHeaderBlockOnly() throws Exception {
    ShortUrlResolver.Response response =
        fetcher.fetch(uri("/plain?a=1"), server.getAddress().getAddress(), true);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.header("x-probe")).isEqualTo("yes");
    assertThat(response.header("Location")).isNull();
    assertThat(response.header("transfer-encoding")).isNull();
    assertThat(lastMethod.get()).isEqualTo("HEAD");
    assertThat(lastTarget.get()).isEqualTo("/plain?a=1");
  }

  @Test
  void shouldReadRedirectLocation() throws Exception {
    ShortUrlResolver.Response response =
        fetcher.fetch(uri("/redir"), server.getAddress().getAddress(), true);
    assertThat(response.status()).isEqualTo(302);
    assertThat(response.header("location")).isEqualTo("https://weidian.com/item.html?itemID=123");
  }
}
