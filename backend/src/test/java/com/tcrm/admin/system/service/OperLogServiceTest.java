package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OperLogServiceTest {
  @Test
  void shouldReturnChineseSummaryAndOnlyContractFields_whenListingLogs() {
    var logs = mock(SysOperLogMapper.class);
    var users = mock(SysUserMapper.class);
    var log = new SysOperLogDO();
    log.setId(7L);
    log.setModule("KOL");
    log.setAction("Save promotion config");
    log.setResult("SUCCESS");
    log.setOperTime(Instant.parse("2026-10-19T00:00:00Z"));
    var page = new Page<SysOperLogDO>(2, 20, 21);
    page.setRecords(List.of(log));
    when(logs.selectPage(any(Page.class), any())).thenReturn(page);

    var result =
        new OperLogService(logs, users, JsonMapper.builder().build())
            .page(new OperLogQuery(null, null, null, null, null, 2, 20), false);

    assertThat(result.items()).extracting(OperLogDTO::summary).containsExactly("保存推广配置");
    assertThat(result.items().getFirst().success()).isTrue();
    assertThat(result.total()).isEqualTo(21);
    assertThat(result.page()).isEqualTo(2);
  }
}
