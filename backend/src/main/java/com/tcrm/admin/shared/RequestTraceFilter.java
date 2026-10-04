package com.tcrm.admin.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String previous = MDC.get("traceId");
    String traceId = UUID.randomUUID().toString().replace("-", "");
    MDC.put("traceId", traceId);
    response.setHeader("X-Trace-Id", traceId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previous);
      }
    }
  }
}
