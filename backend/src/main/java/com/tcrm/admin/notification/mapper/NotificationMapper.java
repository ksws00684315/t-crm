package com.tcrm.admin.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.notification.model.NotificationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper extends BaseMapper<NotificationDO> {
  @Insert(
      "INSERT INTO crm_notification (id, tenant_id, user_id, type, params, biz_type, biz_id, create_time) "
          + "VALUES (#{row.id}, #{row.tenantId}, #{row.userId}, #{row.type}, CAST(#{row.params} AS jsonb), 'PREPAY', #{row.bizId}, #{row.createTime}) "
          + "ON CONFLICT (tenant_id, user_id, biz_type, biz_id, type) WHERE biz_type = 'PREPAY' DO NOTHING")
  int insertPrepayEvent(@Param("row") NotificationDO row);

  @Insert(
      "INSERT INTO crm_notification (id, tenant_id, user_id, type, params, biz_type, biz_id, create_time) "
          + "VALUES (#{row.id}, #{row.tenantId}, #{row.userId}, #{row.type}, CAST(#{row.params} AS jsonb), 'WITHDRAWAL', #{row.bizId}, #{row.createTime}) "
          + "ON CONFLICT (tenant_id, user_id, biz_type, biz_id, type) WHERE biz_type = 'WITHDRAWAL' DO NOTHING")
  int insertWithdrawalEvent(@Param("row") NotificationDO row);
}
