package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import org.junit.jupiter.api.Test;

class AuditWriterTest {
  @Test
  void shouldPersistEvent_whenDatabaseIsAvailable() {
    var logs = mock(SysOperLogMapper.class);
    var event = new SysOperLogDO();
    new AuditWriter(logs).write(event);
    verify(logs).insert(event);
  }

  @Test
  void shouldKeepCallerAvailable_whenAuditPersistenceFails() {
    var logs = mock(SysOperLogMapper.class);
    when(logs.insert(any(SysOperLogDO.class)))
        .thenThrow(new IllegalStateException("unit-database-failure"));
    assertThatCode(() -> new AuditWriter(logs).write(new SysOperLogDO()))
        .doesNotThrowAnyException();
  }
}
