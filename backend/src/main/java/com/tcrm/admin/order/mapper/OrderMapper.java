package com.tcrm.admin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.order.model.OrderDO;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {
  /**
   * Latest ATTRIBUTED pay_time per KOL (task 25a idle cleanup). Empty {@code kolIds} returns an
   * empty list without querying.
   */
  @Select(
      """
      <script>
      SELECT kol_id AS kolId, MAX(pay_time) AS payTime
        FROM crm_order
       WHERE attribution_status = 'ATTRIBUTED'
         AND pay_time IS NOT NULL
         AND is_deleted = FALSE
         AND kol_id IN
         <foreach collection='kolIds' item='id' open='(' separator=',' close=')'>
           #{id}
         </foreach>
       GROUP BY kol_id
      </script>
      """)
  List<KolLastPayRow> lastAttributedPayTimes(@Param("kolIds") Collection<Long> kolIds);

  /** Mapper row for {@link #lastAttributedPayTimes}. */
  class KolLastPayRow {
    private Long kolId;
    private Instant payTime;

    public Long getKolId() {
      return kolId;
    }

    public void setKolId(Long kolId) {
      this.kolId = kolId;
    }

    public Instant getPayTime() {
      return payTime;
    }

    public void setPayTime(Instant payTime) {
      this.payTime = payTime;
    }
  }
}
