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
}
