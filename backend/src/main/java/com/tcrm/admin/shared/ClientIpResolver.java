package com.tcrm.admin.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP. Forwarding headers are honoured only when the direct TCP peer is one
 * of the configured trusted proxies; otherwise the peer address is returned. The same logic is
 * mirrored in the other service (backend and redirect); ClientIpResolverParityTest keeps the copies
 * identical, so change both together.
 */
@Component
public class ClientIpResolver {
  private static final List<String> DEFAULT_HEADERS =
      List.of("CF-Connecting-IP", "X-Forwarded-For");
  private final List<Cidr> trusted;
  private final List<String> headers;

  public ClientIpResolver(
      @Value("${crm.http.trusted-proxies:}") String trustedProxies,
      @Value("${crm.http.client-ip-headers:CF-Connecting-IP,X-Forwarded-For}") String headerNames) {
    this.trusted =
        Arrays.stream(trustedProxies.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Cidr::parse)
            .toList();
    List<String> configured =
        Arrays.stream(headerNames.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    this.headers = configured.isEmpty() ? DEFAULT_HEADERS : configured;
  }

  /** Returns the canonical client IP, or the raw peer address when it is not an IP literal. */
  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    byte[] peerBytes = literal(peer);
    if (peerBytes == null) {
      return peer;
    }
    String canonical = canonical(peerBytes);
    if (!isTrusted(peerBytes)) {
      return canonical;
    }
    for (String header : headers) {
      List<String> hops = hops(request, header);
      for (int i = hops.size() - 1; i >= 0; i--) {
        byte[] hop = literal(hops.get(i));
        if (hop == null) {
          return canonical;
        }
        if (!isTrusted(hop)) {
          return canonical(hop);
        }
      }
    }
    return canonical;
  }

  private boolean isTrusted(byte[] address) {
    return trusted.stream().anyMatch(cidr -> cidr.contains(address));
  }

  private static List<String> hops(HttpServletRequest request, String header) {
    List<String> hops = new ArrayList<>();
    Enumeration<String> lines = request.getHeaders(header);
    while (lines != null && lines.hasMoreElements()) {
      for (String part : lines.nextElement().split(",", -1)) {
        hops.add(part.trim());
      }
    }
    return hops;
  }

  private static String canonical(byte[] address) {
    try {
      return InetAddress.getByAddress(address).getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Parses an IPv4/IPv6 literal without ever triggering a DNS lookup; null when not a literal. */
  private static byte[] literal(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    if (text.isEmpty() || text.length() > 45) {
      return null;
    }
    if (text.indexOf(':') >= 0) {
      if (!text.matches("[0-9A-Fa-f:.]+")) {
        return null;
      }
    } else if (!validIpv4(text)) {
      return null;
    }
    try {
      return InetAddress.getByName(text).getAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static boolean validIpv4(String text) {
    String[] octets = text.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    for (String octet : octets) {
      if (!octet.matches("\\d{1,3}") || Integer.parseInt(octet) > 255) {
        return false;
      }
    }
    return true;
  }

  private record Cidr(byte[] network, int prefix) {
    static Cidr parse(String text) {
      String[] parts = text.split("/", -1);
      byte[] address = literal(parts[0]);
      if (parts.length > 2 || address == null) {
        throw new IllegalArgumentException("Invalid trusted proxy entry: " + text);
      }
      int prefix = address.length * 8;
      if (parts.length == 2) {
        if (!parts[1].matches("\\d{1,3}") || Integer.parseInt(parts[1]) > prefix) {
          throw new IllegalArgumentException("Invalid trusted proxy prefix: " + text);
        }
        prefix = Integer.parseInt(parts[1]);
      }
      return new Cidr(address, prefix);
    }

    boolean contains(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int full = prefix / 8;
      for (int i = 0; i < full; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = 0xFF << (8 - rest) & 0xFF;
      return (address[full] & mask) == (network[full] & mask);
    }
  }
}
