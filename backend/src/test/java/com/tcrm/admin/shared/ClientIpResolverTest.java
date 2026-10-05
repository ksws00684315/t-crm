package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
  private static final String HEADERS = "CF-Connecting-IP,X-Forwarded-For";

  private static MockHttpServletRequest request(String peer) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    return request;
  }

  @Test
  void shouldIgnoreForwardingHeaders_whenNoTrustedProxyIsConfigured() {
    var request = request("203.0.113.2");
    request.addHeader("CF-Connecting-IP", "198.51.100.9");
    request.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(new ClientIpResolver("", HEADERS).resolve(request)).isEqualTo("203.0.113.2");
  }

  @Test
  void shouldIgnoreForwardingHeaders_whenDirectPeerIsNotTrusted() {
    var request = request("203.0.113.2");
    request.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request))
        .isEqualTo("203.0.113.2");
  }

  @Test
  void shouldUseCloudflareHeader_whenDirectPeerIsTrusted() {
    var request = request("10.1.2.3");
    request.addHeader("CF-Connecting-IP", "198.51.100.9");
    request.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request))
        .isEqualTo("198.51.100.9");
  }

  @Test
  void shouldSkipTrustedHopsFromTheRight_whenForwardedForHasSeveralEntries() {
    var request = request("10.1.2.3");
    // The leftmost entry is client-supplied and must not win over the first untrusted hop.
    request.addHeader("X-Forwarded-For", "192.0.2.77, 198.51.100.8, 10.9.9.9");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request))
        .isEqualTo("198.51.100.8");
  }

  @Test
  void shouldJoinRepeatedForwardedForHeaders_whenProxiesAppendSeparateLines() {
    var request = request("10.1.2.3");
    request.addHeader("X-Forwarded-For", "192.0.2.77");
    request.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request))
        .isEqualTo("198.51.100.8");
  }

  @Test
  void shouldFallBackToPeer_whenHeadersAreMissingOrAllTrusted() {
    var resolver = new ClientIpResolver("10.0.0.0/8", HEADERS);
    assertThat(resolver.resolve(request("10.1.2.3"))).isEqualTo("10.1.2.3");
    var request = request("10.1.2.3");
    request.addHeader("X-Forwarded-For", "10.4.4.4, 10.5.5.5");
    assertThat(resolver.resolve(request)).isEqualTo("10.1.2.3");
  }

  @Test
  void shouldFallBackToPeer_whenForwardedValueIsNotAnIpLiteral() {
    var resolver = new ClientIpResolver("10.0.0.0/8", HEADERS);
    for (String bad :
        new String[] {"unknown", "999.1.1.1", "example.com", "198.51.100.8:80", "1.2.3", "::::"}) {
      var request = request("10.1.2.3");
      request.addHeader("X-Forwarded-For", bad);
      assertThat(resolver.resolve(request)).as(bad).isEqualTo("10.1.2.3");
    }
  }

  @Test
  void shouldReturnCanonicalIpv6_whenClientAddressIsIpv6() {
    var request = request("10.1.2.3");
    request.addHeader("X-Forwarded-For", "2001:DB8::1");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request))
        .isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void shouldMatchIpv6AndSingleAddressProxies_whenTheyAreConfigured() {
    var resolver = new ClientIpResolver("127.0.0.1, fd00::/8", HEADERS);
    var ipv4 = request("127.0.0.1");
    ipv4.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(resolver.resolve(ipv4)).isEqualTo("198.51.100.8");
    var ipv6 = request("fd12:3456::1");
    ipv6.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(resolver.resolve(ipv6)).isEqualTo("198.51.100.8");
    var other = request("127.0.0.2");
    other.addHeader("X-Forwarded-For", "198.51.100.8");
    assertThat(resolver.resolve(other)).isEqualTo("127.0.0.2");
  }

  @Test
  void shouldNeverReturnBlank_whenPeerIsUnparseable() {
    var request = request("not-an-ip");
    assertThat(new ClientIpResolver("10.0.0.0/8", HEADERS).resolve(request)).isEqualTo("not-an-ip");
  }

  @Test
  void shouldRejectStartup_whenProxyListIsInvalid() {
    for (String bad : new String[] {"10.0.0.0/33", "bad", "10.0.0.0/x", "::1/129", "*"}) {
      assertThatThrownBy(() -> new ClientIpResolver(bad, HEADERS))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void shouldUseDefaultHeaders_whenHeaderListIsBlank() {
    var request = request("10.1.2.3");
    request.addHeader("CF-Connecting-IP", "198.51.100.9");
    assertThat(new ClientIpResolver("10.0.0.0/8", " , ").resolve(request))
        .isEqualTo("198.51.100.9");
  }
}
