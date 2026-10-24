package com.tcrm.admin.product.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Real transport for {@link ShortUrlResolver}: raw sockets pinned to a pre-validated address so the
 * JVM never performs a second DNS lookup (DNS rebinding, task 57). Only the header block is read;
 * the body is never consumed. HEAD falls back to GET at the caller.
 */
class ShortUrlHttpFetcher implements ShortUrlResolver.Transport {
  static final int CONNECT_TIMEOUT_MS = 3_000;
  static final int READ_TIMEOUT_MS = 5_000;
  private static final int MAX_HEADER_BYTES = 32 * 1024;

  private final SSLSocketFactory tlsFactory;

  ShortUrlHttpFetcher() {
    this((SSLSocketFactory) SSLSocketFactory.getDefault());
  }

  ShortUrlHttpFetcher(SSLSocketFactory tlsFactory) {
    this.tlsFactory = tlsFactory;
  }

  @Override
  public ShortUrlResolver.Response fetch(URI uri, InetAddress address, boolean head)
      throws IOException {
    boolean tls = "https".equalsIgnoreCase(uri.getScheme());
    int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
    try (Socket socket = tls ? tlsFactory.createSocket() : new Socket()) {
      socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
      // The connected peer must be the validated address; anything else means the DNS answer
      // changed between validation and connection, so refuse to talk to it.
      if (!address.equals(socket.getInetAddress())) {
        throw new IOException("Connected address does not match the validated one");
      }
      socket.setSoTimeout(READ_TIMEOUT_MS);
      if (tls) {
        startHandshake((SSLSocket) socket, uri.getHost());
      }
      writeRequest(socket.getOutputStream(), uri, head);
      return readResponse(socket.getInputStream());
    }
  }

  private static void startHandshake(SSLSocket socket, String host) throws IOException {
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    parameters.setServerNames(java.util.List.of(new SNIHostName(host)));
    socket.setSSLParameters(parameters);
    socket.startHandshake();
  }

  private static void writeRequest(OutputStream out, URI uri, boolean head) throws IOException {
    String path = uri.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    String query = uri.getRawQuery();
    String target = query == null ? path : path + "?" + query;
    boolean tls = "https".equalsIgnoreCase(uri.getScheme());
    int port = uri.getPort();
    String hostHeader =
        port > 0 && port != (tls ? 443 : 80) ? uri.getHost() + ":" + port : uri.getHost();
    String request =
        (head ? "HEAD" : "GET")
            + " "
            + target
            + " HTTP/1.1\r\n"
            + "Host: "
            + hostHeader
            + "\r\n"
            + "User-Agent: SparkTrack-ShortUrlResolver\r\n"
            + "Accept: */*\r\n"
            + "Connection: close\r\n"
            + "\r\n";
    out.write(request.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  /** Reads the status line and headers only; stops at the blank line before any body bytes. */
  private static ShortUrlResolver.Response readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int state = 0;
    int total = 0;
    int read;
    while ((read = in.read()) >= 0) {
      total++;
      if (total > MAX_HEADER_BYTES) {
        throw new IOException("Response header block is too large");
      }
      buffer.write(read);
      state =
          switch (state) {
            case 0 -> read == '\r' ? 1 : 0;
            case 1 -> read == '\n' ? 2 : 0;
            case 2 -> read == '\r' ? 3 : 0;
            case 3 -> read == '\n' ? -1 : 0;
            default -> state;
          };
      if (state == -1) {
        break;
      }
    }
    if (state != -1) {
      throw new IOException("Connection closed before the response header block ended");
    }
    String[] lines = buffer.toString(StandardCharsets.ISO_8859_1).trim().split("\r\n");
    int status = parseStatus(lines[0]);
    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = lines[i].substring(colon + 1).trim();
      headers.merge(name, value, (previous, next) -> previous + ", " + next);
    }
    return new ShortUrlResolver.Response(status, headers);
  }

  private static int parseStatus(String statusLine) throws IOException {
    String[] parts = statusLine.trim().split("\\s+");
    if (parts.length < 2) {
      throw new IOException("Malformed HTTP status line");
    }
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("Malformed HTTP status line");
    }
  }
}
