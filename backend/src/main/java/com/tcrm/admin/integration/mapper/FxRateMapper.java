package com.tcrm.admin.integration.mapper;

import com.tcrm.admin.integration.model.FxRateDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code crm_fx_rate} is platform-level reference data without {@code tenant_id} (design §3.4,
 * ADR-020). {@code CompanyTenantLineHandler} always ignores the table, so company-scoped requests
 * can read it.
 */
@Mapper
public interface FxRateMapper {
  /** Inserts the row unless its rate date is already stored; returns 1 when inserted, else 0. */
  @Insert(
      """
      INSERT INTO crm_fx_rate (id, rate_date, base, quote, rate, source, fetched_time, create_by,
        create_time)
      VALUES (#{id}, #{rateDate}, #{base}, #{quote}, #{rate}, #{source}, #{fetchedTime}, NULL,
        #{createTime})
      ON CONFLICT (rate_date) DO NOTHING
      """)
  int insertIgnore(FxRateDO row);

  /** Row with the newest rate date, or null when empty. Uses {@code uk_crm_fx_rate_rate_date}. */
  @Select(
      """
      SELECT id, rate_date, base, quote, rate, source, fetched_time, create_by, create_time
      FROM crm_fx_rate
      ORDER BY rate_date DESC
      LIMIT 1
      """)
  FxRateDO selectLatest();
}
