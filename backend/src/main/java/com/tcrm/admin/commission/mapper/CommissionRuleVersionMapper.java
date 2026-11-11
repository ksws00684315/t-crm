package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.commission.model.CommissionRuleVersionDO;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Company settlement config versions (task 62, requirement 11.03). */
@Mapper
public interface CommissionRuleVersionMapper extends BaseMapper<CommissionRuleVersionDO> {
  /**
   * Explicit columns of {@link CommissionRuleVersionDO} instead of SELECT * (task 145, audit C-02).
   */
  String SELECT_FROM =
      "SELECT id, tenant_id, version_no, currency, minimum_withdrawal_cny, channel_rules, actor_id, create_time FROM crm_commission_rule_version ";

  @Select(
      SELECT_FROM
          + """
      ORDER BY version_no DESC LIMIT 1
      """)
  CommissionRuleVersionDO findLatest();

  @Select(
      SELECT_FROM
          + """
      WHERE create_time <= #{payTime} ORDER BY version_no DESC LIMIT 1
      """)
  CommissionRuleVersionDO findEffectiveAt(@Param("payTime") Instant payTime);
}
