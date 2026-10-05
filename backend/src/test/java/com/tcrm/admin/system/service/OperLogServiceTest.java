package com.tcrm.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperLogServiceTest {
  @Test
  void shouldReturnAuditDtoWithPaging_whenListingLogs() {
    var logs = mock(SysOperLogMapper.class);
    var log = new SysOperLogDO();
    log.setId(7L);
    log.setResult("SUCCESS");
    var page = new Page<SysOperLogDO>(2, 20, 21);
    page.setRecords(List.of(log));
    when(logs.selectPage(any(Page.class), any())).thenReturn(page);
    var result = new OperLogService(logs).page(2, 20);
    assertThat(result.items()).extracting(OperLogDTO::result).containsExactly("SUCCESS");
    assertThat(result.total()).isEqualTo(21);
    assertThat(result.page()).isEqualTo(2);
  }
}
