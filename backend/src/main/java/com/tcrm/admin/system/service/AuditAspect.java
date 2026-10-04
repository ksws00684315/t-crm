package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.LoginSubject;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.system.model.SysOperLogDO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    SysOperLogDO event = new SysOperLogDO();
    event.setModule(annotation.module());
    event.setAction(annotation.action());
    event.setOperTime(Instant.now());
    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    event.setMethod(request.getMethod());
    event.setRequestUri(AuditSanitizer.truncate(request.getRequestURI(), 512));
    event.setIp(AuditSanitizer.truncate(request.getRemoteAddr(), 45));
    event.setRequestParams(
        sanitizer.sanitize(
            Map.of("arguments", point.getArgs(), "query", request.getParameterMap())));
    captureActor(event);
    for (Object arg : point.getArgs()) {
      if (arg instanceof LoginSubject login) {
        event.setUsername(login.username());
      }
    }
    try {
      Object result = point.proceed();
      event.setResult("SUCCESS");
      captureActor(event);
      return result;
    } catch (Throwable e) {
      event.setResult("FAILURE");
      event.setErrorMsg(
          e instanceof BusinessException
              ? AuditSanitizer.truncate(e.getMessage(), 1000)
              : e.getClass().getSimpleName());
      throw e;
    } finally {
      event.setCostMs((System.nanoTime() - start) / 1_000_000);
      try {
        writer.write(event);
      } catch (RuntimeException e) {
        LOG.error("Audit submission failed", e);
      }
    }
  }

  private void captureActor(SysOperLogDO event) {
    try {
      var actor = current.user();
      event.setUserId(actor.id());
      event.setUsername(actor.username());
    } catch (RuntimeException e) {
      // Anonymous login keeps its submitted username; logout retains its previously captured actor.
      return;
    }
  }
}
