package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.LoginSubject;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ClientIpResolver;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.model.SysOperLogDO;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditAspectTest {
  private final AuditWriter writer = mock(AuditWriter.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
  private final AuditLog annotation = mock(AuditLog.class);
  private final ClientIpResolver clientIps = new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For");
  private MockHttpServletRequest request;

  @BeforeEach
  void prepareRequest() {
    request = new MockHttpServletRequest("POST", "/admin-api/auth/login");
    request.setRemoteAddr("10.1.2.3");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(annotation.module()).thenReturn("Auth");
    when(annotation.action()).thenReturn("Login");
    when(sanitizer.sanitize(any())).thenReturn("[REDACTED]");
    when(point.getArgs()).thenReturn(new Object[] {(LoginSubject) () -> "operator"});
  }

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private SysOperLogDO event() {
    var event = org.mockito.ArgumentCaptor.forClass(SysOperLogDO.class);
    verify(writer).write(event.capture());
    return event.getValue();
  }

  @Test
  void shouldCaptureActorAndSuccess_whenOperationCompletes() throws Throwable {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                7L,
                "operator",
                "test-hash",
                "Operator",
                null,
                Role.OPERATOR,
                DataScope.OWN,
                1,
                null,
                null));
    when(point.proceed()).thenReturn("result");
    assertThat(new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation))
        .isEqualTo("result");
    var event = event();
    assertThat(event.getUserId()).isEqualTo(7L);
    assertThat(event.getResult()).isEqualTo("SUCCESS");
    assertThat(event.getRequestParams()).isEqualTo("[REDACTED]");
    assertThat(event.getCostMs()).isNotNegative();
  }

  @Test
  void shouldRecordFailedLoginSubject_whenActorIsAnonymous() throws Throwable {
    when(current.user()).thenThrow(new IllegalStateException());
    when(point.proceed()).thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));
    assertThatThrownBy(
            () -> new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation))
        .isInstanceOf(BusinessException.class);
    var event = event();
    assertThat(event.getUsername()).isEqualTo("operator");
    assertThat(event.getResult()).isEqualTo("FAILURE");
    assertThat(event.getErrorMsg()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.message());
  }

  @Test
  void shouldSuppressInternalErrorDetails_whenUnexpectedOperationFails() throws Throwable {
    when(point.proceed()).thenThrow(new IllegalStateException("unit-private-details"));
    assertThatThrownBy(
            () -> new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation))
        .isInstanceOf(IllegalStateException.class);
    assertThat(event().getErrorMsg()).isEqualTo("IllegalStateException");
  }

  @Test
  void shouldKeepOperationResult_whenAuditSubmissionFails() throws Throwable {
    when(point.getArgs()).thenReturn(new Object[] {"ordinary-input"});
    when(point.proceed()).thenReturn("result");
    doThrow(new IllegalStateException("unit-audit-failure")).when(writer).write(any());
    assertThat(new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation))
        .isEqualTo("result");
  }

  @Test
  void shouldRecordResolvedClientIp_whenRequestComesThroughTrustedProxy() throws Throwable {
    request.addHeader("X-Forwarded-For", "192.0.2.77, 198.51.100.8");
    when(point.proceed()).thenReturn("result");
    new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation);
    assertThat(event().getIp()).isEqualTo("198.51.100.8");
  }

  @Test
  void shouldRecordPeerIp_whenForwardedHeaderComesFromUntrustedPeer() throws Throwable {
    request.setRemoteAddr("203.0.113.2");
    request.addHeader("X-Forwarded-For", "198.51.100.8");
    when(point.proceed()).thenReturn("result");
    new AuditAspect(writer, sanitizer, current, clientIps).audit(point, annotation);
    assertThat(event().getIp()).isEqualTo("203.0.113.2");
  }
}
