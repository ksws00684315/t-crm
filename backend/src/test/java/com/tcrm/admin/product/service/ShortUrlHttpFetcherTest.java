package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

  /**
   * L-009: a whitelisted server that trickles bytes must not stretch one fetch beyond its total
   * deadline. The raw socket server writes one header byte every 50 ms and never ends the header
   * block; the fetcher runs with an 800 ms deadline, so the abort lands near that deadline instead
   * of the 5 s per-read timeout.
   */
  @Test
  void shouldAbortWithinTheTotalDeadline_whenTheServerTricklesHeaderBytes() throws Exception {
    try (ServerSocket drip = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread accepter =
          new Thread(
              () -> {
                try (Socket client = drip.accept()) {
                  client.getInputStream().readAllBytes();
                  OutputStream out = client.getOutputStream();
                  out.write("HTTP/1.1 200 OK\r\nX-Drip: ".getBytes(StandardCharsets.ISO_8859_1));
                  out.flush();
                  while (true) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(50);
                  }
                } catch (Exception ignored) {
                  // the fetcher closed the connection after the deadline
                }
              });
      accepter.setDaemon(true);
      accepter.start();
      // Plain http, so the null TLS factory is never used; the deadline is shortened for the test.
      ShortUrlHttpFetcher fetcher = new ShortUrlHttpFetcher(null, 800);
      long start = System.nanoTime();
      assertThatThrownBy(
              () ->
                  fetcher.fetch(
                      URI.create(
                          "http://"
                              + drip.getInetAddress().getHostAddress()
                              + ":"
                              + drip.getLocalPort()
                              + "/drip"),
                      InetAddress.getLoopbackAddress(),
                      false))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("did not complete within 800 ms");
      // Near the 800 ms deadline — far below the 5 s per-read timeout that ruled before.
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(elapsedMs).isBetween(700L, 3_000L);
    }
  }

  @Test
  void shouldReadRedirectLocation() throws Exception {
    ShortUrlResolver.Response response =
        fetcher.fetch(uri("/redir"), server.getAddress().getAddress(), true);
    assertThat(response.status()).isEqualTo(302);
    assertThat(response.header("location")).isEqualTo("https://weidian.com/item.html?itemID=123");
  }
}
