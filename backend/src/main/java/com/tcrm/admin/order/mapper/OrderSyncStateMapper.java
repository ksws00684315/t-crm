package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderSyncStateMapper extends BaseMapper<OrderSyncStateDO> {}
