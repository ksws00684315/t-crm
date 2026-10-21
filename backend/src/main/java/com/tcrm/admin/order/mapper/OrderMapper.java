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

  /**
   * Per-shop missing-field counts for the monitoring view (task 40 §3.5). CASE sums instead of
   * FILTER clauses: the tenant interceptor re-parses every statement with JSqlParser. The tenant
   * predicate comes from the interceptor.
   */
  @Select(
      """
      SELECT shop_id AS shopId,
             SUM(CASE WHEN pay_time IS NULL THEN 1 ELSE 0 END) AS missingPayTime,
             SUM(CASE WHEN order_time IS NULL THEN 1 ELSE 0 END) AS missingOrderTime,
             SUM(CASE WHEN pay_amount IS NULL THEN 1 ELSE 0 END) AS missingPayAmount,
             SUM(CASE WHEN total_amount IS NULL THEN 1 ELSE 0 END) AS missingTotalAmount
        FROM crm_order
       WHERE is_deleted = FALSE
       GROUP BY shop_id
      """)
  List<ShopQualityRow> missingOrderFields();

  /** Data provenance per shop: the source of the newest order (task 40 §3.5). */
  @Select(
      """
      SELECT shop_id AS shopId, source AS latestSource
        FROM crm_order
       WHERE id IN (SELECT MAX(id) FROM crm_order GROUP BY shop_id)
      """)
  List<ShopSourceRow> latestOrderSourcePerShop();

  /** Mapper row for {@link #missingOrderFields()}. */
  class ShopQualityRow {
    private Long shopId;
    private Long missingPayTime;
    private Long missingOrderTime;
    private Long missingPayAmount;
    private Long missingTotalAmount;

    public Long getShopId() {
      return shopId;
    }

    public void setShopId(Long shopId) {
      this.shopId = shopId;
    }

    public Long getMissingPayTime() {
      return missingPayTime;
    }

    public void setMissingPayTime(Long value) {
      missingPayTime = value;
    }

    public Long getMissingOrderTime() {
      return missingOrderTime;
    }

    public void setMissingOrderTime(Long value) {
      missingOrderTime = value;
    }

    public Long getMissingPayAmount() {
      return missingPayAmount;
    }

    public void setMissingPayAmount(Long value) {
      missingPayAmount = value;
    }

    public Long getMissingTotalAmount() {
      return missingTotalAmount;
    }

    public void setMissingTotalAmount(Long value) {
      missingTotalAmount = value;
    }
  }

  /** Mapper row for {@link #latestOrderSourcePerShop()}. */
  class ShopSourceRow {
    private Long shopId;
    private Integer latestSource;

    public Long getShopId() {
      return shopId;
    }

    public void setShopId(Long shopId) {
      this.shopId = shopId;
    }

    public Integer getLatestSource() {
      return latestSource;
    }

    public void setLatestSource(Integer value) {
      latestSource = value;
    }
  }
}
