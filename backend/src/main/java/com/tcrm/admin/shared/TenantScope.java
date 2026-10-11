package com.tcrm.admin.shared;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit company or system scope for the current thread. The holder is not inherited by pool
 * threads. Closing a scope restores the previous one, so scopes may nest. The innermost frame wins:
 * a company scope inside system scope filters to that company, and a system scope inside a company
 * scope ignores every table.
 */
public final class TenantScope {
  private static final Logger LOG = LoggerFactory.getLogger(TenantScope.class);
  private static final ThreadLocal<Deque<Frame>> STACK = new ThreadLocal<>();

  private TenantScope() {}

  /** Runs {@code action} in {@code tenantId} and restores the previous scope afterwards. */
  public static Scope openCompany(long tenantId) {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenant id must be positive");
    }
    return push(new Frame(tenantId, null));
  }

  /**
   * Runs without a company filter. {@code reason} is required and written at DEBUG so every system
   * scope names why it crosses companies.
   */
  public static Scope openSystem(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("system scope requires a reason");
    }
    LOG.debug("Entering system scope: {}", reason);
    return push(new Frame(null, reason));
  }

  public static void runCompany(long tenantId, Runnable action) {
    try (Scope ignored = openCompany(tenantId)) {
      action.run();
    }
  }

  public static <T> T callCompany(long tenantId, Callable<T> action) {
    try (Scope ignored = openCompany(tenantId)) {
      return call(action);
    }
  }

  public static void runSystem(String reason, Runnable action) {
    try (Scope ignored = openSystem(reason)) {
      action.run();
    }
  }

  public static <T> T callSystem(String reason, Callable<T> action) {
    try (Scope ignored = openSystem(reason)) {
      return call(action);
    }
  }

  /** True when the innermost frame is a system scope. */
  public static boolean inSystem() {
    Frame frame = current();
    return frame != null && frame.reason != null;
  }

  /** Innermost company id, or null when unset or the innermost frame is a system scope. */
  public static Long companyId() {
    Frame frame = current();
    if (frame == null || frame.reason != null) {
      return null;
    }
    return frame.tenantId;
  }

  private static Frame current() {
    Deque<Frame> stack = STACK.get();
    return stack == null || stack.isEmpty() ? null : stack.peek();
  }

  private static Scope push(Frame frame) {
    Deque<Frame> stack = STACK.get();
    if (stack == null) {
      stack = new ArrayDeque<>();
      STACK.set(stack);
    }
    stack.push(frame);
    return new Scope(frame);
  }

  private static <T> T call(Callable<T> action) {
    try {
      return action.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Restores the scope that was current when this frame was opened. Closing twice does nothing. */
  public static final class Scope implements AutoCloseable {
    private final Frame frame;
    private boolean closed;

    private Scope(Frame frame) {
      this.frame = frame;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      Deque<Frame> stack = STACK.get();
      if (stack == null || stack.isEmpty() || stack.peek() != frame) {
        throw new IllegalStateException("tenant scope closed out of order");
      }
      closed = true;
      stack.pop();
      if (stack.isEmpty()) {
        STACK.remove();
      }
    }
  }

  private record Frame(Long tenantId, String reason) {}
}
