package com.tcrm.admin.commission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Append-only commission item revisions (task 62). */
@Mapper
public interface CommissionItemRevisionMapper extends BaseMapper<CommissionItemRevisionDO> {

  /** All revisions of the item states, oldest first; used to show rate snapshots per line. */
  @Select(
      "<script>SELECT * FROM crm_commission_item_revision WHERE item_state_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " ORDER BY item_state_id, create_time, id</script>")
  List<CommissionItemRevisionDO> listByItemStateIds(@Param("ids") Collection<Long> ids);

  /** Latest revision rows (one per item state) for line display. */
  @Select(
      "<script>SELECT r.* FROM crm_commission_item_revision r JOIN ("
          + " SELECT item_state_id, MAX(id) AS max_id FROM crm_commission_item_revision"
          + " WHERE item_state_id IN "
          + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
          + " GROUP BY item_state_id) m ON r.item_state_id = m.item_state_id AND r.id = m.max_id"
          + "</script>")
  List<CommissionItemRevisionDO> listLatestByItemStateIds(@Param("ids") Collection<Long> ids);
}
