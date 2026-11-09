package com.tcrm.admin.kollibrary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kollibrary.model.LibraryKolDO;
import org.apache.ibatis.annotations.Mapper;

/** Creator profiles of the library; the tenant line adds the company predicate. */
@Mapper
public interface LibraryKolMapper extends BaseMapper<LibraryKolDO> {}
