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

  @Select(
      """
      SELECT * FROM crm_commission_rule_version ORDER BY version_no DESC LIMIT 1
      """)
  CommissionRuleVersionDO findLatest();

  @Select(
      """
      SELECT * FROM crm_commission_rule_version
      WHERE create_time <= #{payTime} ORDER BY version_no DESC LIMIT 1
      """)
  CommissionRuleVersionDO findEffectiveAt(@Param("payTime") Instant payTime);
}
