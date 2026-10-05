package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class DistributedLocksTest {
  @Test
  void shouldSkipAction_whenLockCannotBeAcquired() throws Exception {
    var redis = mock(RedissonClient.class);
    var lock = mock(RLock.class);
    when(redis.getLock("job")).thenReturn(lock);
    var action = mock(Runnable.class);
    assertThat(new DistributedLocks(redis).run("job", 0, 10, action)).isFalse();
    verifyNoInteractions(action);
    verify(lock, never()).unlock();
  }

  @Test
  void shouldReleaseOwnedLock_whenActionThrows() throws Exception {
    var redis = mock(RedissonClient.class);
    var lock = mock(RLock.class);
    when(redis.getLock("job")).thenReturn(lock);
    when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenReturn(true);
    assertThatThrownBy(
            () ->
                new DistributedLocks(redis)
                    .run(
                        "job",
                        0,
                        10,
                        () -> {
                          throw new IllegalStateException("unit-failure");
                        }))
        .hasMessage("unit-failure");
    verify(lock).unlock();
  }

  @Test
  void shouldRunActionWithoutUnlockingExpiredLock_whenLeaseHasEnded() throws Exception {
    var redis = mock(RedissonClient.class);
    var lock = mock(RLock.class);
    when(redis.getLock("job")).thenReturn(lock);
    when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    var action = mock(Runnable.class);
    assertThat(new DistributedLocks(redis).run("job", 0, 10, action)).isTrue();
    verify(action).run();
    verify(lock, never()).unlock();
  }

  @Test
  void shouldReportLockState_whenAskedIfLocked() {
    var redis = mock(RedissonClient.class);
    var lock = mock(RLock.class);
    when(redis.getLock("job")).thenReturn(lock);
    when(lock.isLocked()).thenReturn(true);
    assertThat(new DistributedLocks(redis).isLocked("job")).isTrue();
  }
}
