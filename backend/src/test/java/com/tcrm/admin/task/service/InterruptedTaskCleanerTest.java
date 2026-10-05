package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterruptedTaskCleanerTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskDO.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldFailUnfinishedTasks_whenApplicationStarts() {
    TaskMapper tasks = mock(TaskMapper.class);
    when(tasks.update(isNull(), any())).thenReturn(3);

    new InterruptedTaskCleaner(tasks).run(null);

    ArgumentCaptor<Wrapper<TaskDO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(tasks).update(isNull(), wrapper.capture());
    var update = (LambdaUpdateWrapper<TaskDO>) wrapper.getValue();
    assertThat(update.getSqlSet()).contains("status=", "message=", "finished_at=");
    assertThat(update.getSqlSegment()).contains("status IN");
    assertThat(update.getParamNameValuePairs().values())
        .contains(
            TaskStatus.FAILED,
            InterruptedTaskCleaner.MESSAGE,
            TaskStatus.WAITING,
            TaskStatus.RUNNING);
  }
}
