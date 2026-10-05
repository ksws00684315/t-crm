package com.tcrm.admin.shared;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class DistributedLocks {
  private final RedissonClient redis;

  public DistributedLocks(RedissonClient redis) {
    this.redis = redis;
  }

  /**
   * Runs {@code action} while holding the named lock. A non-positive {@code leaseSeconds} keeps the
   * lock alive with Redisson's watchdog until the action returns (for long-running work).
   */
  public boolean run(String name, long waitSeconds, long leaseSeconds, Runnable action)
      throws InterruptedException {
    var lock = redis.getLock(name);
    if (!lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
      return false;
    }
    try {
      action.run();
      return true;
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  /** True when any thread or instance currently holds the named lock. */
  public boolean isLocked(String name) {
    return redis.getLock(name).isLocked();
  }
}
