package com.tcrm.admin.task.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskStatusTest {
  @Test
  void shouldBeSuccess_whenNoRowFailed() {
    assertThat(TaskStatus.finish(10, 0)).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test
  void shouldBePartial_whenSomeRowsFailed() {
    assertThat(TaskStatus.finish(10, 1)).isEqualTo(TaskStatus.PARTIAL);
    assertThat(TaskStatus.finish(10, 9)).isEqualTo(TaskStatus.PARTIAL);
  }

  @Test
  void shouldBeFailed_whenEveryRowFailed() {
    assertThat(TaskStatus.finish(10, 10)).isEqualTo(TaskStatus.FAILED);
    assertThat(TaskStatus.finish(1, 1)).isEqualTo(TaskStatus.FAILED);
  }
}
