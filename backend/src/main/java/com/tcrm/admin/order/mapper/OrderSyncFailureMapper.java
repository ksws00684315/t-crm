package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderSyncFailureMapper extends BaseMapper<OrderSyncFailureDO> {}
