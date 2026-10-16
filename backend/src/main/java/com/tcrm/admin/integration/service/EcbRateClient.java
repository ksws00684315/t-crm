package com.tcrm.admin.integration.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the ECB daily reference rates (design §7.5) with the JDK HTTP client and XML parser. The
 * feed quotes every currency per 1 EUR; only USD and CNY are used.
 */
@Component
public class EcbRateClient {
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** The daily feed is about 2 KB; anything far larger is not the expected document. */
  static final int MAX_BYTES = 64 * 1024;

  static final int RATE_SCALE = 8;

  private final HttpClient http;
  private final URI url;

  public EcbRateClient(@Value("${crm.fx.ecb-url}") String url) {
    this.url = URI.create(url);
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /**
   * Fetches and parses the feed; any network, HTTP or content problem is an {@link
   * EcbFeedException}.
   */
  public EcbRate fetch() {
    HttpResponse<byte[]> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new EcbFeedException("request failed: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EcbFeedException("request interrupted");
    }
    if (response.statusCode() != 200) {
      throw new EcbFeedException("unexpected HTTP status " + response.statusCode());
    }
    return parse(response.body());
  }

  /**
   * Extracts the reference date and converts it to CNY to USD: USD rate / CNY rate, 8 decimals
   * HALF_UP (design §7.5). Rejects DOCTYPE declarations (no XXE), a missing date, a missing USD or
   * CNY rate and non-positive rates.
   */
  static EcbRate parse(byte[] xml) {
    if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) {
      throw new EcbFeedException("empty or oversized document");
    }
    Document document;
    try {
      document = newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    } catch (SAXException | IOException e) {
      throw new EcbFeedException("document is not accepted XML");
    }
    Element day = null;
    NodeList cubes = document.getElementsByTagNameNS("*", "Cube");
    for (int i = 0; i < cubes.getLength() && day == null; i++) {
      Element cube = (Element) cubes.item(i);
      if (cube.hasAttribute("time")) {
        day = cube;
      }
    }
    if (day == null) {
      throw new EcbFeedException("reference date missing");
    }
    LocalDate date;
    try {
      date = LocalDate.parse(day.getAttribute("time"));
    } catch (DateTimeParseException e) {
      throw new EcbFeedException("reference date invalid");
    }
    BigDecimal usd = null;
    BigDecimal cny = null;
    NodeList rates = day.getElementsByTagNameNS("*", "Cube");
    for (int i = 0; i < rates.getLength(); i++) {
      Element rate = (Element) rates.item(i);
      switch (rate.getAttribute("currency")) {
        case "USD" -> usd = positiveRate(rate);
        case "CNY" -> cny = positiveRate(rate);
        default -> {
          // Other currencies are not used.
        }
      }
    }
    if (usd == null || cny == null) {
      throw new EcbFeedException("USD or CNY rate missing");
    }
    return new EcbRate(date, usd.divide(cny, RATE_SCALE, RoundingMode.HALF_UP));
  }

  private static BigDecimal positiveRate(Element cube) {
    BigDecimal value;
    try {
      value = new BigDecimal(cube.getAttribute("rate"));
    } catch (NumberFormatException e) {
      throw new EcbFeedException("rate invalid");
    }
    if (value.signum() <= 0) {
      throw new EcbFeedException("rate not positive");
    }
    return value;
  }

  /** Hardened per the OWASP XXE prevention guidance: no DOCTYPE, no external entities or DTDs. */
  private static DocumentBuilder newDocumentBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      // The default handler prints parse errors to stderr; this one only throws on fatal errors.
      builder.setErrorHandler(new DefaultHandler());
      return builder;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("XML parser does not support the required hardening", e);
    }
  }
}
