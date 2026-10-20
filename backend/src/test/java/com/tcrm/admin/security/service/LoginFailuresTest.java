package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginFailuresTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final LoginFailures failures = new LoginFailures(redis, "auth", configs);

  LoginFailuresTest() {
    when(configs.getInt("login.fail.pair-limit")).thenReturn(5);
    when(configs.getInt("login.fail.ip-limit")).thenReturn(20);
    when(configs.getInt("login.fail.account-limit")).thenReturn(50);
    when(configs.getInt("login.fail.window-minutes")).thenReturn(15);
  }

  @SuppressWarnings("unchecked")
  private List<List<String>> counterKeysOfFailure() {
    var keys = ArgumentCaptor.forClass(List.class);
    verify(redis, atLeastOnce())
        .execute(any(RedisScript.class), keys.capture(), any(Object[].class));
    List<List<String>> result = new ArrayList<>();
    keys.getAllValues().forEach(k -> result.add((List<String>) k));
    return result;
  }

  @Test
  void shouldCountOnPairIpAndAccountScopes_whenAttemptFails() {
    failures.fail("Operator", "198.51.100.8");
    var keys = counterKeysOfFailure();
    assertThat(keys).hasSize(3);
    assertThat(keys.get(0).getFirst()).startsWith("auth:fail:user-ip:");
    assertThat(keys.get(1).getFirst()).startsWith("auth:fail:ip:");
    assertThat(keys.get(2).getFirst()).startsWith("auth:fail:user:");
    keys.forEach(k -> assertThat(k.get(1)).isEqualTo(k.getFirst() + ":lock"));
  }

  @Test
  void shouldUseSeparateKeys_whenKolPrefixIsConfigured() {
    LoginFailures kolFailures = new LoginFailures(redis, "kol:auth");
    kolFailures.fail("kol@example.com", "198.51.100.8");
    assertThat(counterKeysOfFailure())
        .extracting(k -> k.getFirst())
        .allSatisfy(key -> assertThat(key).startsWith("kol:auth:fail:"));
  }

  @Test
  void shouldPassScopeLimitsToScript_whenAttemptFails() {
    failures.fail("operator", "198.51.100.8");
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(redis, atLeastOnce()).execute(any(RedisScript.class), anyList(), args.capture());
    assertThat(args.getAllValues().stream().map(a -> a[3]).toList())
        .containsExactly("5", "20", "50");
  }

  @Test
  void shouldNotLeakUsernameOrIpIntoKeys_whenAttemptFails() {
    failures.fail("operator", "198.51.100.8");
    counterKeysOfFailure()
        .forEach(
            k ->
                assertThat(k.getFirst().toLowerCase())
                    .doesNotContain("operator")
                    .doesNotContain("198.51.100.8"));
  }

  @Test
  void shouldLockAtFifthPairFailure_whenCountReachesLimit() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(1L, 1L, 1L, 2L, 2L, 2L, 3L, 3L, 3L, 4L, 4L, 4L, 5L, 5L, 5L);
    for (int i = 0; i < 4; i++) {
      assertThatCode(() -> failures.fail("operator", "198.51.100.8")).doesNotThrowAnyException();
    }
    assertThat(
            catchThrowableOfType(
                    () -> failures.fail("operator", "198.51.100.8"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
  }

  @Test
  void shouldLockAddress_whenTwentyFailuresComeFromOneIp() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(1L, 20L, 1L);
    assertThat(
            catchThrowableOfType(
                    () -> failures.fail("someone", "198.51.100.8"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
  }

  @Test
  void shouldLockAccountEverywhere_whenFiftyFailuresComeFromManyIps() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(1L, 1L, 50L);
    assertThat(
            catchThrowableOfType(
                    () -> failures.fail("someone", "198.51.100.8"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
  }

  @Test
  void shouldNotBlockVictimOnOtherIp_whenAttackerPairIsLocked() {
    failures.check("operator", "198.51.100.8");
    var keys = ArgumentCaptor.forClass(String.class);
    verify(redis, org.mockito.Mockito.times(3)).hasKey(keys.capture());
    String attackerPairLock = keys.getAllValues().getFirst();
    when(redis.hasKey(anyString()))
        .thenAnswer(call -> call.getArgument(0).equals(attackerPairLock));
    assertThatCode(() -> failures.check("operator", "203.0.113.5")).doesNotThrowAnyException();
    assertThat(
            catchThrowableOfType(
                    () -> failures.check("Operator", "198.51.100.8"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
  }

  @Test
  void shouldRejectLoginAttempt_whenAnyScopeIsLocked() {
    for (String prefix : new String[] {"auth:fail:user-ip:", "auth:fail:ip:", "auth:fail:user:"}) {
      when(redis.hasKey(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0)).startsWith(prefix));
      assertThat(
              catchThrowableOfType(
                      () -> failures.check("operator", "198.51.100.8"), BusinessException.class)
                  .getCode())
          .as(prefix)
          .isEqualTo(429);
    }
  }

  @Test
  void shouldShareCountersAcrossUsernameCase_whenChecking() {
    failures.check("Operator", "198.51.100.8");
    failures.check("operator", "198.51.100.8");
    var keys = ArgumentCaptor.forClass(String.class);
    verify(redis, org.mockito.Mockito.times(6)).hasKey(keys.capture());
    assertThat(keys.getAllValues().subList(0, 3)).isEqualTo(keys.getAllValues().subList(3, 6));
  }

  @Test
  void shouldAcceptLoginCheck_whenCountersAreMissingOrUnlocked() {
    when(redis.hasKey(anyString())).thenReturn(false, null);
    assertThatCode(() -> failures.check("operator", "198.51.100.8")).doesNotThrowAnyException();
    assertThatCode(() -> failures.check("operator", "198.51.100.8")).doesNotThrowAnyException();
  }

  @Test
  void shouldClearOnlyPairCounter_whenLoginSucceeds() {
    failures.clear("operator", "198.51.100.8");
    var key = ArgumentCaptor.forClass(String.class);
    verify(redis).delete(key.capture());
    assertThat(key.getValue()).startsWith("auth:fail:user-ip:");
    verify(redis, never()).delete(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any());
  }

  @Test
  void shouldKeepAuthenticationAvailable_whenRedisScriptReturnsNoCount() {
    assertThatCode(() -> failures.fail("operator", "198.51.100.8")).doesNotThrowAnyException();
  }

  @Test
  void shouldBatchExists_whenFindingLockedUsernames() {
    when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(true, false, true));
    Set<String> locked = failures.findLockedUsernames(List.of("a@x.com", "b@x.com", "c@x.com"));
    assertThat(locked).containsExactlyInAnyOrder("a@x.com", "c@x.com");
    verify(redis).executePipelined(any(RedisCallback.class));
    verify(redis, never()).hasKey(anyString());
  }
}
