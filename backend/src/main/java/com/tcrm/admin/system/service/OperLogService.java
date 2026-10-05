package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.system.mapper.SysOperLogMapper;
import com.tcrm.admin.system.model.SysOperLogDO;
import org.springframework.stereotype.Service;

@Service
public class OperLogService {
  private final SysOperLogMapper logs;

  public OperLogService(SysOperLogMapper logs) {
    this.logs = logs;
  }

  public PageResult<OperLogDTO> page(int page, int size) {
    var result =
        logs.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<SysOperLogDO>()
                .orderByDesc(SysOperLogDO::getOperTime, SysOperLogDO::getId));
    return new PageResult<>(
        result.getRecords().stream().map(OperLogDTO::from).toList(), result.getTotal(), page, size);
  }
}
