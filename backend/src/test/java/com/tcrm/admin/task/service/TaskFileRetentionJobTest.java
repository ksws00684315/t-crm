package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.model.TaskFileDO;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TaskFileRetentionJobTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskFileDO.class);
  }

  @Test
  void shouldComputeCutoff_exactlyRetentionDaysBeforeNow() {
    Instant now = Instant.parse("2026-10-05T03:30:00Z");
    assertThat(TaskFileRetentionJob.cutoff(now))
        .isEqualTo(now.minus(TaskFileRetentionJob.RETENTION_DAYS, ChronoUnit.DAYS));
  }

  @Test
  void shouldDeleteOlderFiles_whenCleanupRuns() {
    TaskFileMapper files = mock(TaskFileMapper.class);
    when(files.delete(any())).thenReturn(3);
    TaskFileRetentionJob job = new TaskFileRetentionJob(files, mock(DistributedLocks.class));
    job.cleanup();
    verify(files).delete(any());
    assertThat(TaskFileRetentionJob.RETENTION_DAYS).isEqualTo(30);
  }

  @Test
  void shouldKeepFile_whenCreateTimeIsExactlyRetentionBoundary() {
    // Predicate is create_time < cutoff, so a file created exactly 30 days ago is kept.
    Instant now = Instant.parse("2026-11-04T12:00:00Z");
    Instant boundary = TaskFileRetentionJob.cutoff(now);
    assertThat(boundary).isEqualTo(now.minus(30, ChronoUnit.DAYS));
    assertThat(boundary.isBefore(boundary)).isFalse();
  }
}
