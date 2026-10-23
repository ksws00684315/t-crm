package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import com.tcrm.admin.system.model.SystemConfigDefinition;
import com.tcrm.admin.system.service.SystemConfigService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Behaviour rules for the sheet-script short-URL resolution (task 57): allow-list, SSRF, hops. */
class ShortUrlResolverTest {
  private static final String URL = "https://s23.cn/abc";
  private static final String HOST_ANSWER = "93.184.216.34";
  private final Map<String, InetAddress> dnsAnswers = new HashMap<>();
  private final List<Recorded> requests = new ArrayList<>();
  private final AtomicReference<List<Answer>> answers = new AtomicReference<>(new ArrayList<>());
  private final AtomicReference<String> allowedHosts =
      new AtomicReference<>("s23.cn,weidian.com,example.com");

  private record Recorded(URI uri, InetAddress address, boolean head) {}

  private record Answer(int status, String location) {}

  private ShortUrlResolver resolver() {
    return resolver(uri -> null);
  }

  private ShortUrlResolver resolver(TransportOverride override) {
    return new ShortUrlResolver(
        config(),
        host -> {
          InetAddress found = dnsAnswers.get(host);
          if (found == null) {
            throw new UnknownHostException(host);
          }
          return new InetAddress[] {found};
        },
        (uri, address, head) -> {
          requests.add(new Recorded(uri, address, head));
          List<Answer> queue = answers.get();
          if (!queue.isEmpty()) {
            Answer answer = queue.remove(0);
            Map<String, String> headers = new HashMap<>();
            if (answer.location() != null) {
              headers.put("location", answer.location());
            }
            return new ShortUrlResolver.Response(answer.status(), headers);
          }
          IOException failure = override.failure(uri);
          if (failure != null) {
            throw failure;
          }
          throw new IOException("No scripted response for " + uri);
        });
  }

  @FunctionalInterface
  private interface TransportOverride {
    IOException failure(URI uri) throws IOException;
  }

  private SystemConfigApi config() {
    return new SystemConfigApi() {
      @Override
      public String getValue(String key) {
        if (ShortUrlResolver.ALLOWED_HOSTS_KEY.equals(key)) {
          return allowedHosts.get();
        }
        throw new IllegalArgumentException("Unknown key " + key);
      }

      @Override
      public int getInt(String key) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private void hostAnswersTo(String host, String literal) throws Exception {
    dnsAnswers.put(host, InetAddress.getByName(literal));
  }

  @Test
  void shouldReturnOriginalUrl_whenThereIsNoRedirect() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(200, null));
    assertThat(resolver().resolve(URL)).isEqualTo(URL);
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).head()).isTrue();
    assertThat(requests.get(0).address()).isEqualTo(dnsAnswers.get("s23.cn"));
  }

  @Test
  void shouldFollowRedirectChain_andReturnFinalAddress() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    hostAnswersTo("weidian.com", "93.184.216.35");
    answers.get().add(new Answer(301, "https://weidian.com/item.html?itemID=123"));
    answers.get().add(new Answer(302, "/item.html?itemID=456"));
    answers.get().add(new Answer(200, null));
    assertThat(resolver().resolve(URL)).isEqualTo("https://weidian.com/item.html?itemID=456");
    assertThat(requests).hasSize(3);
    assertThat(requests.get(2).head()).isTrue();
    assertThat(requests.get(2).uri().toString())
        .isEqualTo("https://weidian.com/item.html?itemID=456");
    assertThat(requests.get(2).address()).isEqualTo(dnsAnswers.get("weidian.com"));
  }

  @Test
  void shouldReject_whenRedirectChainLeavesTheAllowList() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(302, "https://other.example.org/next"));
    assertThatThrownBy(() -> resolver().resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED));
    assertThat(requests).hasSize(1);
  }

  @Test
  void shouldReject_whenMoreThanFiveRedirects() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    for (int i = 0; i < 6; i++) {
      answers.get().add(new Answer(302, "https://s23.cn/hop-" + (i + 1)));
    }
    assertThatThrownBy(() -> resolver().resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_RESOLVE_FAILED));
    assertThat(requests).hasSize(6);
  }

  @Test
  void shouldRejectHostsOutsideTheAllowList_butAcceptExactAndSubdomainMatches() throws Exception {
    assertThat(ShortUrlResolver.parseAllowedHosts(" *.Weidian.com , s23.cn,,EVIL.com "))
        .containsExactly("weidian.com", "s23.cn", "evil.com");
    allowedHosts.set("weidian.com");
    hostAnswersTo("shop.weidian.com", HOST_ANSWER);
    answers.get().add(new Answer(200, null));
    assertThat(resolver().resolve("https://shop.weidian.com/x"))
        .isEqualTo("https://shop.weidian.com/x");
    assertThatThrownBy(() -> resolver().resolve("https://evilweidian.com/x"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED));
    assertThatThrownBy(() -> resolver().resolve("https://weidian.com.evil.com/x"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED));
  }

  @Test
  void shouldPickUpConfigurationChangesImmediately() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(200, null));
    assertThat(resolver().resolve(URL)).isEqualTo(URL);
    allowedHosts.set("other.com");
    answers.get().add(new Answer(200, null));
    assertThatThrownBy(() -> resolver().resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED));
  }

  @Test
  void shouldRejectNonHttpSchemesAndOverlongAddresses() {
    assertThatThrownBy(() -> resolver().resolve("ftp://s23.cn/a"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_INVALID));
    assertThatThrownBy(() -> resolver().resolve("s23.cn/abc"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_INVALID));
    assertThatThrownBy(() -> resolver().resolve("https://s23.cn/" + "a".repeat(2048)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_INVALID));
  }

  @Test
  void shouldRejectPrivateLoopbackLinkLocalAndReservedAddresses() throws Exception {
    for (String literal :
        new String[] {
          "127.0.0.1",
          "10.1.2.3",
          "172.16.0.9",
          "192.168.1.1",
          "169.254.169.254",
          "100.64.1.1",
          "0.0.0.0",
          "224.0.0.1",
          "240.0.0.1",
          "192.0.2.1",
          "::1",
          "fe80::1",
          "fc00::1",
          "2001:db8::1"
        }) {
      hostAnswersTo("s23.cn", literal);
      assertThatThrownBy(() -> resolver().resolve(URL))
          .as("address %s must be rejected", literal)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_ADDRESS_NOT_ALLOWED));
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void shouldAcceptPublicAddresses() throws Exception {
    assertThat(ShortUrlResolver.isPublicAddress(InetAddress.getByName("93.184.216.34"))).isTrue();
    assertThat(ShortUrlResolver.isPublicAddress(InetAddress.getByName("2606:2800:220:1::1")))
        .isTrue();
  }

  @Test
  void shouldRetryWithGet_whenHeadIsNotAllowed() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(405, null));
    answers.get().add(new Answer(200, null));
    assertThat(resolver().resolve(URL)).isEqualTo(URL);
    assertThat(requests.get(0).head()).isTrue();
    assertThat(requests.get(1).head()).isFalse();
  }

  @Test
  void shouldReject_whenResolveTimesOut() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    ShortUrlResolver timingOut = resolver(uri -> new SocketTimeoutException("read timed out"));
    assertThatThrownBy(() -> timingOut.resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_RESOLVE_FAILED));
    assertThat(requests).hasSize(1);
  }

  @Test
  void shouldReject_whenHostCannotBeResolved() {
    assertThatThrownBy(() -> resolver().resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_RESOLVE_FAILED));
    assertThat(requests).isEmpty();
  }

  @Test
  void shouldRejectRedirectTargetsWithoutHttpScheme() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(302, "ftp://s23.cn/next"));
    assertThatThrownBy(() -> resolver().resolve(URL))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHORT_URL_RESOLVE_FAILED));
  }

  @Test
  void shouldReturnCurrentUrl_whenRedirectHasNoLocationHeader() throws Exception {
    hostAnswersTo("s23.cn", HOST_ANSWER);
    answers.get().add(new Answer(302, null));
    assertThat(resolver().resolve(URL)).isEqualTo(URL);
  }

  @Test
  void shouldDefaultToTheThirtyTwoScriptDomains() {
    List<String> defaults =
        ShortUrlResolver.parseAllowedHosts(
            SystemConfigDefinition.SHORT_URL_ALLOWED_HOSTS.fallback());
    assertThat(defaults).hasSize(32);
    assertThat(defaults)
        .contains(
            "cnfans.com",
            "kakobuy.com",
            "oopbuy.com",
            "weidian.com",
            "vdian.com",
            "youshop10.com",
            "koudai.com");
    assertThat(String.join(",", defaults).length())
        .isLessThanOrEqualTo(ShortUrlResolver.MAX_URL_LENGTH);
    assertThatThrownBy(
            () ->
                SystemConfigService.validate(
                    SystemConfigDefinition.SHORT_URL_ALLOWED_HOSTS, "a".repeat(2049)))
        .isInstanceOf(BusinessException.class);
    SystemConfigService.validate(
        SystemConfigDefinition.SHORT_URL_ALLOWED_HOSTS,
        SystemConfigDefinition.SHORT_URL_ALLOWED_HOSTS.fallback());
  }
}
