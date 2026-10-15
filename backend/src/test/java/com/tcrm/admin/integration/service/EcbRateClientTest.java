package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EcbRateClientTest {
  private static final String ENVELOPE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
          xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
        <Cube><Cube time='2026-10-15'>%s</Cube></Cube>
      </gesmes:Envelope>
      """;

  @Test
  void shouldConvertUsdAndCnyToCnyUsd_whenParsingTheDailyFeed() throws IOException {
    EcbRate rate = EcbRateClient.parse(fixture("ecb-2026-10-15.xml"));

    // 1.0812 USD/EUR ÷ 7.7123 CNY/EUR = 0.140191641922... → 0.14019164 (8 decimals, HALF_UP)
    assertThat(rate.date()).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(rate.cnyToUsd()).isEqualByComparingTo("0.14019164");
    assertThat(rate.cnyToUsd().scale()).isEqualTo(8);
  }

  @Test
  void shouldParseTheRealEcbFile_whenItListsEveryCurrency() throws IOException {
    // Unmodified ECB file downloaded on 2026-10-15 (29 currencies, single-quoted attributes).
    EcbRate rate = EcbRateClient.parse(fixture("ecb-real-2026-10-15.xml"));

    // 1.1208 USD/EUR ÷ 7.4993 CNY/EUR = 0.149453948... → 0.14945395
    assertThat(rate.date()).isEqualTo(LocalDate.of(2026, 10, 15));
    assertThat(rate.cnyToUsd()).isEqualByComparingTo("0.14945395");
  }

  @Test
  void shouldRoundHalfUpAtTheEighthDecimal_whenTheQuotientEndsInFive() {
    // 1.000000005 ÷ 1 = 1.000000005 → 1.00000001 with HALF_UP (HALF_EVEN would give 1.00000000)
    EcbRate rate =
        EcbRateClient.parse(
            xml("<Cube currency='USD' rate='1.000000005'/><Cube currency='CNY' rate='1'/>"));

    assertThat(rate.cnyToUsd()).isEqualTo(new BigDecimal("1.00000001"));
  }

  @Test
  void shouldReject_whenCnyIsMissing() {
    assertThatThrownBy(() -> EcbRateClient.parse(xml("<Cube currency='USD' rate='1.0812'/>")))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("USD or CNY rate missing");
  }

  @Test
  void shouldReject_whenUsdIsMissing() {
    assertThatThrownBy(() -> EcbRateClient.parse(xml("<Cube currency='CNY' rate='7.7123'/>")))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("USD or CNY rate missing");
  }

  @Test
  void shouldReject_whenARateIsZero() {
    assertThatThrownBy(
            () ->
                EcbRateClient.parse(
                    xml("<Cube currency='USD' rate='1.0812'/><Cube currency='CNY' rate='0'/>")))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("rate not positive");
  }

  @Test
  void shouldReject_whenARateIsNegative() {
    assertThatThrownBy(
            () ->
                EcbRateClient.parse(
                    xml("<Cube currency='USD' rate='-1'/><Cube currency='CNY' rate='7.7'/>")))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("rate not positive");
  }

  @Test
  void shouldReject_whenARateIsNotANumber() {
    assertThatThrownBy(
            () ->
                EcbRateClient.parse(
                    xml("<Cube currency='USD' rate='abc'/><Cube currency='CNY' rate='7.7'/>")))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("rate invalid");
  }

  @Test
  void shouldReject_whenTheDocumentDeclaresADoctypeWithAnExternalEntity() {
    String xxe =
        """
        <?xml version="1.0"?>
        <!DOCTYPE Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <Envelope><Cube><Cube time='2026-10-15'>&xxe;
          <Cube currency='USD' rate='1.0812'/><Cube currency='CNY' rate='7.7123'/>
        </Cube></Cube></Envelope>
        """;

    assertThatThrownBy(() -> EcbRateClient.parse(xxe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("document is not accepted XML");
  }

  @Test
  void shouldReject_whenTheReferenceDateIsMissing() {
    byte[] noDate =
        "<Envelope><Cube><Cube currency='USD' rate='1'/></Cube></Envelope>"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> EcbRateClient.parse(noDate))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("reference date missing");
  }

  @Test
  void shouldReject_whenTheReferenceDateIsInvalid() {
    byte[] badDate =
        "<Envelope><Cube><Cube time='15/10/26'><Cube currency='USD' rate='1'/></Cube></Cube></Envelope>"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> EcbRateClient.parse(badDate))
        .isInstanceOf(EcbFeedException.class)
        .hasMessage("reference date invalid");
  }

  @Test
  void shouldReject_whenTheBodyIsEmptyOrOversized() {
    assertThatThrownBy(() -> EcbRateClient.parse(new byte[0]))
        .hasMessage("empty or oversized document");
    assertThatThrownBy(() -> EcbRateClient.parse(new byte[EcbRateClient.MAX_BYTES + 1]))
        .hasMessage("empty or oversized document");
  }

  @Test
  void shouldFetchAndParse_whenTheServerReturns200() throws IOException {
    HttpServer server = serve(200, fixture("ecb-2026-10-15.xml"));
    try {
      EcbRate rate = new EcbRateClient(url(server)).fetch();

      assertThat(rate.cnyToUsd()).isEqualByComparingTo("0.14019164");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void shouldThrowFeedException_whenTheServerReturnsAnError() throws IOException {
    HttpServer server = serve(503, "busy".getBytes(StandardCharsets.UTF_8));
    try {
      assertThatThrownBy(() -> new EcbRateClient(url(server)).fetch())
          .isInstanceOf(EcbFeedException.class)
          .hasMessage("unexpected HTTP status 503");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void shouldThrowFeedException_whenTheConnectionFails() throws IOException {
    HttpServer server = serve(200, new byte[0]);
    String url = url(server);
    server.stop(0);

    assertThatThrownBy(() -> new EcbRateClient(url).fetch())
        .isInstanceOf(EcbFeedException.class)
        .hasMessageStartingWith("request failed: ");
  }

  private static byte[] xml(String rates) {
    return ENVELOPE.formatted(rates).getBytes(StandardCharsets.UTF_8);
  }

  static byte[] fixture(String name) throws IOException {
    try (InputStream in = EcbRateClientTest.class.getResourceAsStream("/fx/" + name)) {
      return in.readAllBytes();
    }
  }

  private static HttpServer serve(int status, byte[] body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    return server;
  }

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/eurofxref-daily.xml";
  }
}
