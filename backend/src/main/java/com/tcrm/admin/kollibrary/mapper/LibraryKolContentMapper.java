package com.tcrm.admin.kollibrary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Content records of the library; the tenant line adds the company predicate. */
@Mapper
public interface LibraryKolContentMapper extends BaseMapper<LibraryKolContentDO> {
  @Select(
      "SELECT CAST(weekly_data AS text) FROM crm_library_kol_content WHERE id = #{id} AND is_deleted = FALSE")
  String weeklyData(Long id);

  @Update(
      """
      UPDATE crm_library_kol_content
      SET weekly_data = CAST(#{weeklyData} AS jsonb)
      WHERE id = #{id} AND is_deleted = FALSE
      """)
  int saveWeeklyData(Long id, String weeklyData);
}
