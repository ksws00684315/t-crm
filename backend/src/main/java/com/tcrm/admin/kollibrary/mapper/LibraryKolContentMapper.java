package com.tcrm.admin.kollibrary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcrm.admin.kollibrary.model.LibraryKolContentDO;
import org.apache.ibatis.annotations.Mapper;

/** Content records of the library; the tenant line adds the company predicate. */
@Mapper
public interface LibraryKolContentMapper extends BaseMapper<LibraryKolContentDO> {}
