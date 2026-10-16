package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import java.util.List;

/** Whitelisted normalized data, with no external JSON. Used only inside order services. */
public record OrderPayloadDTO(OrderDO order, List<OrderItemDO> items) {}
