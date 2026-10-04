package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTraceFilterTest {
  @Test
  void shouldRestoreMdcAndSetHeaders_whenDownstreamThrows() {
    MDC.put("traceId", "outer-trace");
    var response = new MockHttpServletResponse();
    try {
      assertThatThrownBy(
              () ->
                  new RequestTraceFilter()
                      .doFilter(
                          new MockHttpServletRequest(),
                          response,
                          (request, reply) -> {
                            assertThat(MDC.get("traceId")).isNotEqualTo("outer-trace");
                            throw new jakarta.servlet.ServletException("unit-failure");
                          }))
          .isInstanceOf(jakarta.servlet.ServletException.class);
      assertThat(MDC.get("traceId")).isEqualTo("outer-trace");
      assertThat(response.getHeader("X-Trace-Id")).hasSize(32);
      assertThat(response.getHeader("Referrer-Policy"))
          .isEqualTo("strict-origin-when-cross-origin");
    } finally {
      MDC.clear();
    }
  }
}
