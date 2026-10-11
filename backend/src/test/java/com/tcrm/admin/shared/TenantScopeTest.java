package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantScopeTest {
  @AfterEach
  void clearScope() {
    assertThat(TenantScope.companyId()).isNull();
    assertThat(TenantScope.inSystem()).isFalse();
  }

  @Test
  void shouldRestoreOuterScope_whenNestedFrameCloses() {
    try (var outer = TenantScope.openCompany(2L)) {
      assertThat(TenantScope.companyId()).isEqualTo(2L);
      try (var system = TenantScope.openSystem("inner system")) {
        assertThat(TenantScope.inSystem()).isTrue();
        assertThat(TenantScope.companyId()).isNull();
        try (var inner = TenantScope.openCompany(9L)) {
          assertThat(TenantScope.companyId()).isEqualTo(9L);
          assertThat(TenantScope.inSystem()).isFalse();
        }
        assertThat(TenantScope.inSystem()).isTrue();
      }
      assertThat(TenantScope.companyId()).isEqualTo(2L);
      assertThat(TenantScope.inSystem()).isFalse();
    }
  }

  @Test
  void shouldRejectBlankReasonAndNonPositiveCompany() {
    assertThatThrownBy(() -> TenantScope.openSystem("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantScope.openSystem(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantScope.openCompany(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantScope.openCompany(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldIgnoreSecondClose_andRejectOutOfOrderClose() {
    var scope = TenantScope.openCompany(4L);
    scope.close();
    scope.close();
    assertThat(TenantScope.companyId()).isNull();

    var outer = TenantScope.openCompany(1L);
    var inner = TenantScope.openCompany(2L);
    assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
    assertThat(TenantScope.companyId()).isEqualTo(2L);
    inner.close();
    outer.close();
  }

  @Test
  void shouldDropScope_whenActionThrows() {
    assertThatThrownBy(
            () ->
                TenantScope.callCompany(
                    3L,
                    () -> {
                      throw new IllegalStateException("stop");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stop");
    assertThatThrownBy(
            () ->
                TenantScope.callSystem(
                    "checked",
                    () -> {
                      throw new Exception("checked");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .cause()
        .isInstanceOf(Exception.class);
  }

  @Test
  void shouldNotInheritScope_andShouldNotLeakOntoTheNextPoolTask() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
              () -> {
                try (var scope = TenantScope.openCompany(3L)) {
                  assertThat(TenantScope.companyId()).isEqualTo(3L);
                }
                assertThat(TenantScope.companyId()).isNull();
                return null;
              })
          .get();
      try (var scope = TenantScope.openCompany(4L)) {
        Long seen = pool.submit(TenantScope::companyId).get();
        assertThat(seen).isNull();
        assertThat(TenantScope.companyId()).isEqualTo(4L);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
