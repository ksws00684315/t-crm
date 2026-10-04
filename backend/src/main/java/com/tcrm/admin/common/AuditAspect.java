package com.tcrm.admin.common;

import com.tcrm.admin.security.AuthController;
import com.tcrm.admin.security.CurrentUserContext;
import com.tcrm.admin.system.OperLog;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Aspect
@Component
public class AuditAspect {
  private static final Logger LOG = LoggerFactory.getLogger(AuditAspect.class);
  private final AuditWriter writer;
  private final AuditSanitizer sanitizer;
  private final CurrentUserContext current;

  public AuditAspect(AuditWriter writer, AuditSanitizer sanitizer, CurrentUserContext current) {
    this.writer = writer;
    this.sanitizer = sanitizer;
    this.current = current;
  }

  @Around("@annotation(annotation)")
  public Object audit(ProceedingJoinPoint point, AuditLog annotation) throws Throwable {
    long start = System.nanoTime();
    OperLog event = new OperLog();
    event.module = annotation.module();
    event.action = annotation.action();
    event.operTime = Instant.now();
    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    event.method = request.getMethod();
    event.requestUri = AuditSanitizer.truncate(request.getRequestURI(), 512);
    event.ip = AuditSanitizer.truncate(request.getRemoteAddr(), 45);
    event.requestParams =
        sanitizer.sanitize(
            Map.of("arguments", point.getArgs(), "query", request.getParameterMap()));
    captureActor(event);
    for (Object arg : point.getArgs())
      if (arg instanceof AuthController.LoginRequest login) event.username = login.username();
    try {
      Object result = point.proceed();
      event.result = "SUCCESS";
      captureActor(event);
      return result;
    } catch (Throwable e) {
      event.result = "FAILURE";
      event.errorMsg =
          e instanceof BusinessException
              ? AuditSanitizer.truncate(e.getMessage(), 1000)
              : e.getClass().getSimpleName();
      throw e;
    } finally {
      event.costMs = (System.nanoTime() - start) / 1_000_000;
      try {
        writer.write(event);
      } catch (RuntimeException e) {
        LOG.error("Audit submission failed", e);
      }
    }
  }

  private void captureActor(OperLog event) {
    try {
      var actor = current.user();
      event.userId = actor.id;
      event.username = actor.username;
    } catch (RuntimeException e) {
      /* Anonymous login and completed logout have no current actor. */
    }
  }
}
