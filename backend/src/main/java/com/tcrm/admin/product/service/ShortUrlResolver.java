package com.tcrm.admin.product.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves short URLs for the customer sheet script by following redirects manually (task 57 /
 * ADR-019). Every hop re-checks the allow-list from system configuration, and the socket always
 * connects to a pre-validated public address so DNS rebinding cannot bypass the checks. Logs carry
 * host names only, never the query part of the address.
 */
@Component
public class ShortUrlResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ShortUrlResolver.class);
  static final String ALLOWED_HOSTS_KEY = "link.short-url.allowed-hosts";
  static final int MAX_REDIRECTS = 5;
  static final int MAX_URL_LENGTH = 2048;

  /** Resolves host names; replaced with stubs in tests so no unit test touches the network. */
  public interface Dns {
    InetAddress[] resolve(String host) throws UnknownHostException;

    Dns SYSTEM = InetAddress::getAllByName;
  }

  /** Performs one HTTP exchange against an already validated address; headers only. */
  public interface Transport {
    Response fetch(URI uri, InetAddress address, boolean head) throws IOException;

    Transport SYSTEM = new ShortUrlHttpFetcher();
  }

  /** HTTP response with the header block only; header names are lower-cased. */
  record Response(int status, Map<String, String> headers) {
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  private final SystemConfigApi configs;
  private final Dns dns;
  private final Transport transport;

  @Autowired
  public ShortUrlResolver(SystemConfigApi configs) {
    this(configs, Dns.SYSTEM, Transport.SYSTEM);
  }

  ShortUrlResolver(SystemConfigApi configs, Dns dns, Transport transport) {
    this.configs = configs;
    this.dns = dns;
    this.transport = transport;
  }

  /** Returns the final address after following at most {@value MAX_REDIRECTS} redirects. */
  public String resolve(String rawUrl) {
    URI first = parse(rawUrl);
    try {
      URI current = first;
      for (int redirects = 0; ; redirects++) {
        String host = hostOf(current);
        requireAllowedHost(host);
        InetAddress address = publicAddressOf(host);
        Response response = fetch(current, address);
        URI next = isRedirect(response.status()) ? redirectTarget(current, response) : current;
        if (next.equals(current)) {
          LOG.info("Short URL resolved host={} redirects={}", hostOf(first), redirects);
          return current.toString();
        }
        if (redirects == MAX_REDIRECTS) {
          throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
        }
        current = next;
      }
    } catch (BusinessException e) {
      LOG.info("Short URL rejected host={} reason={}", hostOf(first), e.getErrorCode());
      throw e;
    }
  }

  private Response fetch(URI uri, InetAddress address) {
    try {
      Response response = transport.fetch(uri, address, true);
      if (response.status() == 405 || response.status() == 501) {
        response = transport.fetch(uri, address, false);
      }
      return response;
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
    }
  }

  /** Returns the next hop, or the current URI when there is no usable Location header. */
  private URI redirectTarget(URI current, Response response) {
    String location = response.header("location");
    if (location == null || location.isBlank()) {
      return current;
    }
    URI next;
    try {
      next = current.resolve(location.trim());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
    }
    String scheme = next.getScheme();
    if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || next.getHost() == null
        || next.getHost().isBlank()
        || next.toString().length() > MAX_URL_LENGTH) {
      throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
    }
    return next;
  }

  private URI parse(String rawUrl) {
    String trimmed = rawUrl == null ? "" : rawUrl.trim();
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new BusinessException(ErrorCode.SHORT_URL_INVALID);
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || host == null
        || host.isBlank()
        || trimmed.length() > MAX_URL_LENGTH) {
      throw new BusinessException(ErrorCode.SHORT_URL_INVALID);
    }
    return uri;
  }

  private static String hostOf(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new BusinessException(ErrorCode.SHORT_URL_INVALID);
    }
    return host.toLowerCase(Locale.ROOT);
  }

  private void requireAllowedHost(String host) {
    for (String allowed : parseAllowedHosts()) {
      if (host.equals(allowed) || host.endsWith("." + allowed)) {
        return;
      }
    }
    throw new BusinessException(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED);
  }

  static List<String> parseAllowedHosts(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .map(host -> host.toLowerCase(Locale.ROOT))
        .map(host -> host.startsWith("*.") ? host.substring(2) : host)
        .filter(host -> !host.isEmpty())
        .toList();
  }

  private List<String> parseAllowedHosts() {
    return parseAllowedHosts(configs.getValue(ALLOWED_HOSTS_KEY));
  }

  private InetAddress publicAddressOf(String host) {
    InetAddress[] addresses;
    try {
      addresses = dns.resolve(host);
    } catch (UnknownHostException e) {
      throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
    }
    if (addresses == null || addresses.length == 0) {
      throw new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED);
    }
    for (InetAddress address : addresses) {
      if (!isPublicAddress(address)) {
        throw new BusinessException(ErrorCode.SHORT_URL_ADDRESS_NOT_ALLOWED);
      }
    }
    return addresses[0];
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /** Rejects loopback, private, link-local, multicast, and reserved addresses (SSRF, task 57). */
  static boolean isPublicAddress(InetAddress address) {
    if (address instanceof Inet4Address) {
      return isPublicIpv4(address.getAddress());
    }
    byte[] bytes = address.getAddress();
    boolean documentation =
        (bytes[0] & 255) == 32
            && (bytes[1] & 255) == 1
            && (bytes[2] & 255) == 13
            && (bytes[3] & 255) == 184;
    return !address.isAnyLocalAddress()
        && !address.isLoopbackAddress()
        && !address.isLinkLocalAddress()
        && !address.isMulticastAddress()
        && !address.isSiteLocalAddress()
        && (bytes[0] & 254) != 252
        && !documentation;
  }

  private static boolean isPublicIpv4(byte[] address) {
    int first = address[0] & 255;
    int second = address[1] & 255;
    int third = address[2] & 255;
    if (first == 0
        || first == 10
        || first == 127
        || (first == 100 && (second & 192) == 64)
        || (first == 169 && second == 254)
        || (first == 172 && (second & 240) == 16)
        || (first == 192 && second == 0 && (third == 0 || third == 2))
        || (first == 192 && second == 168)
        || (first == 198 && (second & 254) == 18)
        || (first == 198 && second == 51 && third == 100)
        || (first == 203 && second == 0 && third == 113)
        || (first & 240) == 224
        || first >= 240) {
      return false;
    }
    return true;
  }

  /** Lower-cases keys and joins repeated ones; only used by the real transport (tests stub it). */
  static Map<String, String> headerMap(Map<String, List<String>> rawHeaders) {
    Map<String, String> headers = new TreeMap<>();
    rawHeaders.forEach(
        (name, values) -> {
          if (name != null && !values.isEmpty()) {
            headers.merge(name.toLowerCase(Locale.ROOT), String.join(", ", values), (a, b) -> a);
          }
        });
    return headers;
  }
}
