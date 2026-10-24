package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.ProductLinkRateLimiter;
import com.tcrm.admin.product.service.ShortUrlResolver;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** Envelope, rate limiting, and error-code mapping for the public resolve endpoint (task 57). */
class ShortUrlControllerTest {
  private static final String BASE = "/admin-api/external/short-urls";
  private final ShortUrlResolver resolver = mock(ShortUrlResolver.class);
  private final ProductLinkRateLimiter rates = mock(ProductLinkRateLimiter.class);
  private final ClientIpResolver ips = mock(ClientIpResolver.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    when(ips.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");
    mvc = AdminMvcSupport.buildPublic(new ShortUrlController(resolver, rates, ips));
  }

  @Test
  void shouldResolve_withEnvelopeDataUrl() throws Exception {
    when(resolver.resolve("https://s23.cn/abc"))
        .thenReturn("https://weidian.com/item.html?itemID=123");
    mvc.perform(get(BASE + "/resolve").param("url", "https://s23.cn/abc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.url").value("https://weidian.com/item.html?itemID=123"));
    verify(rates).checkIp("127.0.0.1");
  }

  @Test
  void shouldReturn429_whenIpIsRateLimited() throws Exception {
    doThrow(new BusinessException(ErrorCode.PRODUCT_LINK_RATE_LIMITED))
        .when(rates)
        .checkIp("127.0.0.1");
    mvc.perform(get(BASE + "/resolve").param("url", "https://s23.cn/abc"))
        .andExpect(status().is(429))
        .andExpect(jsonPath("$.errorCode").value("PRODUCT_LINK_RATE_LIMITED"));
  }

  @Test
  void shouldReturn422_whenHostIsNotAllowed() throws Exception {
    when(resolver.resolve("https://evil.example.com/x"))
        .thenThrow(new BusinessException(ErrorCode.SHORT_URL_HOST_NOT_ALLOWED));
    mvc.perform(get(BASE + "/resolve").param("url", "https://evil.example.com/x"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.errorCode").value("SHORT_URL_HOST_NOT_ALLOWED"));
  }

  @Test
  void shouldReturn502_whenResolutionFails() throws Exception {
    when(resolver.resolve("https://s23.cn/abc"))
        .thenThrow(new BusinessException(ErrorCode.SHORT_URL_RESOLVE_FAILED));
    mvc.perform(get(BASE + "/resolve").param("url", "https://s23.cn/abc"))
        .andExpect(status().is(502))
        .andExpect(jsonPath("$.errorCode").value("SHORT_URL_RESOLVE_FAILED"));
  }

  @Test
  void shouldReturn400_whenUrlParameterIsMissing() throws Exception {
    mvc.perform(get(BASE + "/resolve")).andExpect(status().isBadRequest());
  }

  @Test
  void shouldResolveEncodedAddresses_fromQueryParameters() throws Exception {
    when(resolver.resolve(eq("https://s23.cn/a b")))
        .thenReturn("https://weidian.com/item.html?itemID=9");
    mvc.perform(get(BASE + "/resolve").param("url", "https://s23.cn/a b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.url").value("https://weidian.com/item.html?itemID=9"));
  }
}
